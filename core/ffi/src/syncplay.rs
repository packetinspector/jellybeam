//! docs/28 §4: the account's Watch Together owner. [`SyncPlayOwner`] lives on the installed
//! account, is stepped only under the state lock through `installed_mut(epoch)`, runs the reducer's
//! effects without blocking, and re-derives what it executes from the reducer's state after every
//! step. The FFI mirrors here redact names and ids in `Debug` (docs/28 §3.6).

use crate::error::{CallbackError, CoreError, UnreachableReason};
use crate::object::{JellybeamCore, SharedState};
use crate::signin::unreachable_reason;
use jellyfin_api::models::{BufferRequestDto, PlayRequestDto, QueueRequestDto, ReadyRequestDto};
use jellyfin_api::{ApiError, GroupUpdate, JellyfinClient, SyncPlayEvent};
use playback_policy::syncplay::{
    self as sp, Effect, Event, Job, RestCall, Snapshot, SyncPlay, Tag,
};
use std::collections::VecDeque;
use std::sync::{Arc, OnceLock, Weak};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::runtime::Handle;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinSet;
use uuid::Uuid;

/// docs/28 §4: the group and what executes it. `exec` is derived from `group` after every step:
/// None while Idle, Live while Creating, Joining or Member, Draining while Leaving.
#[derive(Default)]
pub(crate) struct SyncPlayOwner {
    group: SyncPlay,
    exec: Exec,
}

/// Dropping it drops the update sender (its delivery task drains, then releases the listener) and
/// aborts every job; a Leave already sent is never among them.
#[derive(Default)]
pub(crate) enum Exec {
    /// docs/28 §8: Idle holds no heap, task or listener.
    #[default]
    None,
    Live {
        deliver: UpdateTx,
        jobs: Jobs,
    },
    /// The listener stays for the exit's Detach; the membership's jobs are gone. `left` settles
    /// once the newest Leave, and its resend after an unanswered entry, is answered; an account
    /// reset hands it to the logout.
    Draining {
        deliver: UpdateTx,
        left: Option<Settle>,
    },
}

/// Unbounded: one send per step, under the state lock, so steps bound it.
type UpdateTx = mpsc::UnboundedSender<SyncPlayUpdate>;

/// Fires once a request has an answer; a dropped sender reads as answered too.
pub(crate) type Settle = oneshot::Receiver<()>;

/// A runner request; `answered` (New and Join only) fires once the request has an answer.
type Request = (Tag, RestCall, Option<oneshot::Sender<()>>);

/// The membership's scope: the serial request runner, the time sync and timers.
pub(crate) struct Jobs {
    set: JoinSet<()>,
    /// Unbounded: at most a few requests per step, sent under the state lock, so steps bound it.
    requests: mpsc::UnboundedSender<Request>,
    /// docs/28 §4: settles once the newest New or Join has an answer; a Leave sent before then is
    /// sent once more after it, so an entry that reaches the server last is undone too.
    entry: Option<Settle>,
}

/// What a step's async work needs: where to spawn, how to step results back (only while `epoch`
/// is installed), and the account's own client.
pub(crate) struct StepCtx {
    rt: Handle,
    state: Weak<SharedState>,
    epoch: u64,
    client: JellyfinClient,
}

/// One step's leftovers: the retired Exec, dropped by the caller after the state lock is released,
/// and the settle of a Leave the step sent that no Draining group holds.
pub(crate) type Stepped = (Exec, Option<Settle>);

impl SyncPlayOwner {
    pub(crate) fn snapshot(&self) -> SyncPlaySnapshot {
        self.group.snapshot().into()
    }

    fn is_idle(&self) -> bool {
        self.group.is_idle()
    }

    /// docs/28 §4: the one writer, under the state lock.
    pub(crate) fn step_locked(&mut self, ctx: &StepCtx, event: Event) -> Stepped {
        let mut update = SyncPlayUpdate::default();
        let mut left = None;
        let mut cancelled = false;
        let fx = self.group.step(event, now());
        // docs/28 §4: only an exit owes a Leave after an unanswered entry; a ghost Leave keeps it.
        let exiting = self.group.is_idle() || self.group.is_leaving();
        for effect in fx {
            match effect {
                Effect::Rest {
                    req,
                    call: RestCall::Leave,
                } => {
                    let entry = match &mut self.exec {
                        Exec::Live { jobs, .. } if exiting => jobs.unanswered_entry(),
                        _ => None,
                    };
                    left = Some(spawn_leave(ctx, req, entry));
                }
                Effect::Rest { req, call } => match &mut self.exec {
                    Exec::Live { jobs, .. } => jobs.request(req, call),
                    _ => {
                        tracing::warn!(call = call_name(&call), "syncplay request outside a group")
                    }
                },
                Effect::Dir { seq, directive } => update.directives.push(SyncPlayDirectiveAt {
                    seq: seq.0,
                    directive: directive.into(),
                }),
                Effect::Ui(notice) => update.notices.push(notice.into()),
                Effect::Notify => update.changed = true,
                Effect::Job(job) => {
                    if let Exec::Live { jobs, .. } = &mut self.exec {
                        jobs.spawn(ctx, job);
                    }
                }
                Effect::CancelScope => cancelled = true,
            }
        }
        let retired = self.derive_exec(update);
        if let Exec::Draining { left: newest, .. } = &mut self.exec {
            if left.is_some() {
                *newest = left.take();
            }
        }
        debug_assert!(
            !cancelled || !matches!(self.exec, Exec::Live { .. }),
            "a cancelled scope leaves Live"
        );
        debug_assert!(self.exec_matches_group(), "Exec follows the group state");
        (retired, left)
    }

    /// Delivers `update`, then moves Exec to the one the post-step state implies; returns what it
    /// retired.
    fn derive_exec(&mut self, update: SyncPlayUpdate) -> Exec {
        let deliver = match &self.exec {
            Exec::Live { deliver, .. } | Exec::Draining { deliver, .. } => Some(deliver),
            Exec::None => None,
        };
        if let Some(deliver) = deliver.filter(|_| !update.is_empty()) {
            let _ = deliver.send(update);
        }
        if self.group.is_idle() {
            return std::mem::take(&mut self.exec);
        }
        match std::mem::take(&mut self.exec) {
            Exec::Live { deliver, jobs } if self.group.is_leaving() => {
                self.exec = Exec::Draining {
                    deliver: deliver.clone(),
                    left: None,
                };
                Exec::Live { deliver, jobs }
            }
            kept => {
                self.exec = kept;
                Exec::None
            }
        }
    }

    fn exec_matches_group(&self) -> bool {
        let (idle, leaving) = (self.group.is_idle(), self.group.is_leaving());
        match self.exec {
            Exec::None => idle,
            Exec::Live { .. } => !idle && !leaving,
            Exec::Draining { .. } => leaving,
        }
    }

    /// docs/28 §1, §3.3: an Idle group's create or join, executed by a fresh Live scope that
    /// delivers to `listener`; a refusal delivers its notice and returns to None.
    fn enter_locked(
        &mut self,
        ctx: &StepCtx,
        listener: Arc<dyn SyncPlayListener>,
        event: Event,
    ) -> Stepped {
        self.exec = Exec::live(ctx, listener);
        self.step_locked(ctx, event)
    }

    /// docs/28 §4: steps AccountReset on the outgoing account's group, already out of the state,
    /// which leaves on that account's own client from Creating, Joining or Member (once more after
    /// an unanswered entry) and not otherwise; returns that Leave's settle, or the in-flight one's.
    pub(crate) fn take_for_reset(mut self, ctx: &StepCtx) -> Option<Settle> {
        let (retired, sent) = self.step_locked(ctx, Event::AccountReset);
        match retired {
            Exec::Draining { left, .. } => sent.or(left),
            _ => sent,
        }
    }

    /// docs/28 §4: at core teardown, the group's Leave goes out at once (as at an account reset) and
    /// is awaited up to [`SHUTDOWN_LEAVE_BUDGET`], its resend best-effort; on a runtime thread, where
    /// a wait would panic, nothing is awaited.
    pub(crate) fn leave_on_shutdown(self, ctx: &StepCtx) {
        let left = self.take_for_reset(ctx);
        if let Some(left) = left.filter(|_| Handle::try_current().is_err()) {
            let _ = ctx
                .rt
                .block_on(async { tokio::time::timeout(SHUTDOWN_LEAVE_BUDGET, left).await });
        }
    }
}

/// docs/28 §4: how long a dropping core waits for its group's Leave to be answered.
const SHUTDOWN_LEAVE_BUDGET: Duration = Duration::from_secs(1);

/// docs/28 §5: how often a Ready or Buffering lost to a transport error or 5xx is resent, and
/// how long apart.
const REPORT_RETRIES: u32 = 3;
const REPORT_RETRY_MS: u64 = 1_000;

impl Exec {
    fn live(ctx: &StepCtx, listener: Arc<dyn SyncPlayListener>) -> Self {
        let mut set = JoinSet::new();
        let requests = spawn_runner(&mut set, ctx);
        Exec::Live {
            deliver: spawn_delivery(&ctx.rt, listener),
            jobs: Jobs {
                set,
                requests,
                entry: None,
            },
        }
    }
}

impl Jobs {
    /// Queues `call` for the runner; a New or Join also leaves the settle a Leave waits for.
    fn request(&mut self, req: Tag, call: RestCall) {
        let answered = matches!(call, RestCall::New { .. } | RestCall::Join { .. }).then(|| {
            let (answered, entry) = oneshot::channel();
            self.entry = Some(entry);
            answered
        });
        let _ = self.requests.send((req, call, answered));
    }

    /// The settle of a New or Join that has no answer yet; an answered one is dropped.
    fn unanswered_entry(&mut self) -> Option<Settle> {
        let mut entry = self.entry.take()?;
        matches!(entry.try_recv(), Err(oneshot::error::TryRecvError::Empty)).then_some(entry)
    }

    /// Arms `job` in the scope, reaping finished jobs first so the set never grows.
    fn spawn(&mut self, ctx: &StepCtx, job: Job) {
        while self.set.try_join_next().is_some() {}
        let (state, epoch, client) = (ctx.state.clone(), ctx.epoch, ctx.client.clone());
        match job {
            Job::TimeSync { membership } => {
                self.set
                    .spawn_on(time_sync(state, epoch, client, membership), &ctx.rt);
            }
            Job::After { delay_ms, timer } => {
                let delay = match timer {
                    // Scaled /10 in tests: a rig's setup must not outlast it.
                    sp::Timer::EntryTimeout { .. } if cfg!(test) => {
                        Duration::from_millis(delay_ms / 10)
                    }
                    _ => scaled(delay_ms),
                };
                self.set.spawn_on(
                    async move {
                        tokio::time::sleep(delay).await;
                        sync_play_feed(&state, epoch, Event::Timer(timer));
                    },
                    &ctx.rt,
                );
            }
        }
    }
}

impl StepCtx {
    pub(crate) fn new(
        rt: Handle,
        state: Weak<SharedState>,
        epoch: u64,
        client: JellyfinClient,
    ) -> Self {
        Self {
            rt,
            state,
            epoch,
            client,
        }
    }
}

/// docs/28 §4: steps `event` into the group of the account installed under `epoch`, dropping it
/// once that account is gone; returns whether it stepped. It spawns on the current runtime, so it is
/// called from runtime tasks only (forwarder, runner, jobs).
pub(crate) fn sync_play_feed(state: &Weak<SharedState>, epoch: u64, event: Event) -> bool {
    let Some(shared) = state.upgrade() else {
        return false;
    };
    let Ok(rt) = Handle::try_current() else {
        tracing::warn!("syncplay feed outside the runtime");
        return false;
    };
    let stepped = {
        let mut guard = shared.lock();
        let Some(installed) = guard.account.installed_mut(epoch) else {
            return false;
        };
        let ctx = StepCtx::new(rt, state.clone(), epoch, installed.client.clone());
        installed.syncplay.step_locked(&ctx, event)
    };
    drop(stepped);
    true
}

/// docs/28 §2, §4: the "not in the group" signal, which rejoins a connected member once.
pub(crate) fn rejoin_event() -> Event {
    Event::Server(SyncPlayEvent::GroupUpdate {
        group_id: None,
        update: GroupUpdate::NotInGroup(String::new()),
    })
}

/// One delivery task per listener, so updates arrive in step order; a throw is logged without its
/// payload and the next update still goes out. Ends, releasing the listener, when the sender drops.
fn spawn_delivery(rt: &Handle, listener: Arc<dyn SyncPlayListener>) -> UpdateTx {
    let (tx, mut rx) = mpsc::unbounded_channel::<SyncPlayUpdate>();
    rt.spawn(async move {
        while let Some(update) = rx.recv().await {
            if listener.on_step(update).is_err() {
                tracing::warn!("syncplay listener threw");
            }
        }
    });
    tx
}

/// docs/28 §5: member requests go out one at a time in step order (a Ready overtaking its
/// Buffering would leave us the silent member); results step back as events. A New or Join runs
/// detached, so its answer still reaches a Leave sent after the scope dropped.
fn spawn_runner(set: &mut JoinSet<()>, ctx: &StepCtx) -> mpsc::UnboundedSender<Request> {
    let (tx, mut rx) = mpsc::unbounded_channel::<Request>();
    let (state, epoch, client) = (ctx.state.clone(), ctx.epoch, ctx.client.clone());
    set.spawn_on(
        async move {
            // Requests taken off the channel early, while a report waited to be resent.
            let mut backlog = VecDeque::new();
            loop {
                let next = match backlog.pop_front() {
                    Some(request) => Some(request),
                    None => rx.recv().await,
                };
                let Some((req, call, answered)) = next else {
                    break;
                };
                let result = match answered {
                    Some(answered) => {
                        let (client, call) = (client.clone(), call.clone());
                        let entry = tokio::spawn(async move {
                            let result = send(&client, &call).await;
                            let _ = answered.send(());
                            result
                        });
                        let Ok(result) = entry.await else {
                            sync_play_feed(&state, epoch, Event::EntryFailed { req });
                            continue;
                        };
                        result
                    }
                    None => send_with_retry(&client, &call, &mut rx, &mut backlog).await,
                };
                if let Err(e) = &result {
                    log_failed(call_name(&call), e);
                }
                for event in result_events(req, &call, result) {
                    sync_play_feed(&state, epoch, event);
                }
            }
        },
        &ctx.rt,
    );
    tx
}

/// docs/28 §5: sends `call`; a Ready or Buffering lost to a [`transient`] failure is resent up to
/// [`REPORT_RETRIES`] times a second apart, unless a newer one of its kind is queued. The runner is
/// serial, so a failing report delays the requests queued behind it by up to 3 s.
async fn send_with_retry(
    client: &JellyfinClient,
    call: &RestCall,
    rx: &mut mpsc::UnboundedReceiver<Request>,
    backlog: &mut VecDeque<Request>,
) -> Result<(), ApiError> {
    let report = matches!(call, RestCall::Ready(_) | RestCall::Buffering(_));
    let mut retries = 0;
    loop {
        let result = send(client, call).await;
        let resend = report && result.as_ref().is_err_and(transient);
        if !resend || retries == REPORT_RETRIES || drain_finds_newer(call, rx, backlog) {
            return result;
        }
        retries += 1;
        tokio::time::sleep(scaled(REPORT_RETRY_MS)).await;
        if drain_finds_newer(call, rx, backlog) {
            return result;
        }
    }
}

/// docs/28 §5: whether a failed report is worth resending: a connection failure or a 5xx, never a
/// timeout, read from the transport text as sign-in reads it.
fn transient(error: &ApiError) -> bool {
    match error {
        ApiError::Transport(text) => unreachable_reason(text) != UnreachableReason::TimedOut,
        ApiError::Status { code, .. } => (500..=599).contains(code),
        ApiError::Decode(_) | ApiError::Unauthorized { .. } => false,
    }
}

/// Moves every queued request into `backlog`; whether a newer request of `call`'s kind is there.
fn drain_finds_newer(
    call: &RestCall,
    rx: &mut mpsc::UnboundedReceiver<Request>,
    backlog: &mut VecDeque<Request>,
) -> bool {
    while let Ok(request) = rx.try_recv() {
        backlog.push_back(request);
    }
    let kind = std::mem::discriminant(call);
    backlog
        .iter()
        .any(|(_, queued, _)| std::mem::discriminant(queued) == kind)
}

/// docs/28 §2, §4: a failed New or Join is EntryFailed; a Seek's answer is RequestDone;
/// a 403 on any other member call means we are no longer in the group, which rejoins once.
fn result_events(req: Tag, call: &RestCall, result: Result<(), ApiError>) -> Vec<Event> {
    let forbidden = matches!(result, Err(ApiError::Status { code: 403, .. }));
    match call {
        RestCall::New { .. } | RestCall::Join { .. } if result.is_err() => {
            vec![Event::EntryFailed { req }]
        }
        RestCall::New { .. } | RestCall::Join { .. } => Vec::new(),
        RestCall::Seek { .. } if forbidden => vec![Event::RequestDone { req }, rejoin_event()],
        RestCall::Seek { .. } => vec![Event::RequestDone { req }],
        _ if forbidden => vec![rejoin_event()],
        _ => Vec::new(),
    }
}

/// docs/28 §4: a Leave is never in the scope, so a cancel or Idle can't abort it. It goes out at
/// once; while `entry`, a New or Join, is unanswered, it goes once more after that answer, however
/// late. Then RequestDone steps back and the settle fires.
fn spawn_leave(ctx: &StepCtx, req: Tag, mut entry: Option<Settle>) -> Settle {
    let (settled, settle) = oneshot::channel();
    let (state, epoch, client) = (ctx.state.clone(), ctx.epoch, ctx.client.clone());
    ctx.rt.spawn(async move {
        loop {
            // Outside a group the server refuses it, which is harmless.
            if let Err(e) = send(&client, &RestCall::Leave).await {
                log_failed("Leave", &e);
            }
            let Some(answer) = entry.take() else { break };
            // The request's own timeout bounds this; a logout keeps its own budget.
            let _ = answer.await;
        }
        sync_play_feed(&state, epoch, Event::RequestDone { req });
        let _ = settled.send(());
    });
    settle
}

/// docs/28 §5: one sample at once, three a second apart, then one a minute; a failure retries
/// after at least a second. Each answer steps back as a TimeSample, whose Ping goes via the runner.
async fn time_sync(state: Weak<SharedState>, epoch: u64, client: JellyfinClient, membership: Tag) {
    let mut successes = 0u32;
    let mut wait_ms = sp::time_sync_delay_ms(0);
    loop {
        tokio::time::sleep(scaled(wait_ms)).await;
        let sent_wall_ms = wall_ms();
        match client.get_utc_time().await {
            Ok(response) => {
                successes = successes.saturating_add(1);
                let sample = sp::TimeSample {
                    membership,
                    sent_wall_ms,
                    received_wall_ms: wall_ms(),
                    response,
                };
                if !sync_play_feed(&state, epoch, Event::TimeSample(sample)) {
                    return;
                }
                wait_ms = sp::time_sync_delay_ms(successes);
            }
            Err(e) => {
                log_failed("GetUtcTime", &e);
                wait_ms = sp::time_sync_retry_ms(successes);
            }
        }
    }
}

async fn send(client: &JellyfinClient, call: &RestCall) -> Result<(), ApiError> {
    let when = chrono::DateTime::<chrono::Utc>::from_timestamp_millis;
    match call {
        RestCall::New { name } => client.sync_play_new_group(&name.0).await.map(drop),
        RestCall::Join { group } => client.sync_play_join_group(*group).await,
        RestCall::Leave => client.sync_play_leave_group().await,
        RestCall::Ping { ms } => client.sync_play_ping(*ms).await,
        RestCall::Ready(r) => {
            let body = ReadyRequestDto {
                is_playing: Some(r.is_playing),
                playlist_item_id: Some(r.pid),
                position_ticks: Some(r.position_ticks),
                when: when(r.when_ms),
            };
            client.sync_play_ready(&body).await
        }
        RestCall::Buffering(r) => {
            let body = BufferRequestDto {
                is_playing: Some(r.is_playing),
                playlist_item_id: Some(r.pid),
                position_ticks: Some(r.position_ticks),
                when: when(r.when_ms),
            };
            client.sync_play_buffering(&body).await
        }
        RestCall::Pause => client.sync_play_pause().await,
        RestCall::Unpause => client.sync_play_unpause().await,
        RestCall::Seek { ticks } => client.sync_play_seek(*ticks).await,
        RestCall::SetNewQueue(q) => {
            let body = PlayRequestDto {
                playing_item_position: Some(q.index),
                playing_queue: q.items.clone(),
                start_position_ticks: Some(q.start_ticks),
            };
            client.sync_play_set_new_queue(&body).await
        }
        RestCall::Queue(a) => {
            let body = QueueRequestDto {
                item_ids: a.items.clone(),
                mode: Some(a.mode),
            };
            client.sync_play_queue(&body).await
        }
        RestCall::NextItem { pid } => client.sync_play_next_item(*pid).await,
        RestCall::PreviousItem { pid } => client.sync_play_previous_item(*pid).await,
    }
}

/// docs/28 §3.6: what a log may say about a call; never its body or names.
fn call_name(call: &RestCall) -> &'static str {
    match call {
        RestCall::New { .. } => "New",
        RestCall::Join { .. } => "Join",
        RestCall::Leave => "Leave",
        RestCall::Ping { .. } => "Ping",
        RestCall::Ready(_) => "Ready",
        RestCall::Buffering(_) => "Buffering",
        RestCall::Pause => "Pause",
        RestCall::Unpause => "Unpause",
        RestCall::Seek { .. } => "Seek",
        RestCall::SetNewQueue(_) => "SetNewQueue",
        RestCall::Queue(_) => "Queue",
        RestCall::NextItem { .. } => "NextItem",
        RestCall::PreviousItem { .. } => "PreviousItem",
    }
}

/// docs/28 §3.6: all that is said of a failure, its class and HTTP status (0 without one); a
/// Status's Display embeds the server body and a Decode's can quote values.
fn error_class(error: &ApiError) -> (&'static str, u16) {
    match error {
        ApiError::Status { code, .. } => ("http", *code),
        ApiError::Transport(_) => ("transport", 0),
        ApiError::Decode(_) => ("decode", 0),
        ApiError::Unauthorized { .. } => ("unauthorized", 401),
    }
}

/// Logs a failed call by name and [`error_class`].
fn log_failed(call: &'static str, error: &ApiError) {
    let (class, status) = error_class(error);
    tracing::warn!(call, class, status, "syncplay request failed");
}

/// The reducer's clocks; `mono_ms` counts from the first step so every caller shares one origin.
fn now() -> sp::Now {
    static MONO_ORIGIN: OnceLock<Instant> = OnceLock::new();
    let origin = MONO_ORIGIN.get_or_init(Instant::now);
    sp::Now {
        mono_ms: u64::try_from(origin.elapsed().as_millis()).unwrap_or(u64::MAX),
        wall_ms: wall_ms(),
    }
}

fn wall_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// A reducer delay as a duration; tests run every delay a hundred times faster.
fn scaled(ms: u64) -> Duration {
    Duration::from_millis(if cfg!(test) { ms / 100 } else { ms })
}

#[uniffi::export]
impl JellybeamCore {
    /// docs/28 §1: what the screens show of the group of the account installed under
    /// `account_epoch`.
    pub fn sync_play_snapshot(&self, account_epoch: u64) -> Result<SyncPlaySnapshot, CoreError> {
        let mut state = self.lock_state();
        let installed = state
            .account
            .installed_mut(account_epoch)
            .ok_or(CoreError::AccountChanged)?;
        Ok(installed.syncplay.snapshot())
    }

    /// docs/28 §1: the name a create posts, "{user}'s group" with the first free suffix among the
    /// listed groups, as jellyfin-web names it; a 403 list names it plainly, since the New is refused
    /// the same way. Its own call, so a caller cancelled during the list posts nothing.
    pub fn sync_play_room_name(&self, account_epoch: u64) -> Result<String, CoreError> {
        let (client, user_name) = {
            let mut state = self.lock_state();
            let installed = state
                .account
                .installed_mut(account_epoch)
                .ok_or(CoreError::AccountChanged)?;
            (installed.client.clone(), installed.user_name().to_string())
        };
        let base = format!("{user_name}'s group");
        let name = match self.list_groups_blocking(account_epoch, &client)? {
            Listing::Groups(groups) => sp::room_name(&base, &groups),
            Listing::NoAccess => sp::RoomName(base),
        };
        Ok(name.0)
    }

    /// docs/28 §1: creates a group named `name` (from [`Self::sync_play_room_name`]); refused while
    /// anything plays or the bus is down. `listener` receives every step's update until the group
    /// is Idle again; already in a group: the snapshot, `listener` not registered.
    pub fn sync_play_create(
        &self,
        account_epoch: u64,
        name: String,
        listener: Arc<dyn SyncPlayListener>,
    ) -> Result<SyncPlaySnapshot, CoreError> {
        // docs/28 §3.3: the server seeds a group from our now-playing, so a just-stopped one's
        // Stopped lands first.
        self.await_final_stops();
        let stepped;
        let snapshot = {
            let mut state = self.lock_state();
            let reporting = if Self::playing_epoch_locked(&state).is_some() {
                sp::Reporting::Live
            } else {
                sp::Reporting::Idle
            };
            let bus = Self::sync_play_bus_locked(&state);
            let installed = state
                .account
                .installed_mut(account_epoch)
                .ok_or(CoreError::AccountChanged)?;
            if !installed.syncplay.is_idle() {
                return Ok(installed.syncplay.snapshot());
            }
            let ctx = self.sync_play_ctx(account_epoch, &installed.client);
            let create = Event::Create(sp::NewGroup {
                name: sp::RoomName(name),
                reporting,
                bus,
            });
            stepped = installed.syncplay.enter_locked(&ctx, listener, create);
            installed.syncplay.snapshot()
        };
        drop(stepped);
        Ok(snapshot)
    }

    /// docs/28 §1: joins the room `group_id` from the room list, never listing; a malformed id is
    /// refused before any step. `listener` receives every step's update until the group is Idle
    /// again; already in a group: that group's snapshot, `listener` not registered.
    pub fn sync_play_join_room(
        &self,
        account_epoch: u64,
        group_id: String,
        listener: Arc<dyn SyncPlayListener>,
    ) -> Result<SyncPlaySnapshot, CoreError> {
        let group = parse_id(&group_id)?;
        let stepped;
        let snapshot = {
            let mut state = self.lock_state();
            let bus = Self::sync_play_bus_locked(&state);
            let installed = state
                .account
                .installed_mut(account_epoch)
                .ok_or(CoreError::AccountChanged)?;
            if !installed.syncplay.is_idle() {
                return Ok(installed.syncplay.snapshot());
            }
            let ctx = self.sync_play_ctx(account_epoch, &installed.client);
            let me = sp::Name(installed.user_name().to_string());
            let join = Event::Join { group, bus, me };
            stepped = installed.syncplay.enter_locked(&ctx, listener, join);
            installed.syncplay.snapshot()
        };
        drop(stepped);
        Ok(snapshot)
    }

    /// docs/28 §3.1: the groups the server lists to this account, a plain read that steps nothing.
    /// A 403 is NoAccess; an account replaced during the call is AccountChanged.
    pub fn sync_play_list_rooms(&self, account_epoch: u64) -> Result<SyncPlayRooms, CoreError> {
        let client = {
            let mut state = self.lock_state();
            let installed = state
                .account
                .installed_mut(account_epoch)
                .ok_or(CoreError::AccountChanged)?;
            installed.client.clone()
        };
        let groups = match self.list_groups_blocking(account_epoch, &client)? {
            Listing::Groups(groups) => groups,
            Listing::NoAccess => return Ok(SyncPlayRooms::NoAccess),
        };
        let rooms = groups
            .into_iter()
            .filter_map(|g| {
                Some(SyncPlayRoom {
                    group_id: g.group_id?.to_string(),
                    name: g.group_name.unwrap_or_default(),
                    participants: g.participants,
                    phase: sp::group_phase(g.state).map(Into::into),
                })
            })
            .collect();
        Ok(SyncPlayRooms::Listed { rooms })
    }

    /// docs/28 §4: one player report or user action into the group; returns the snapshot after it.
    pub fn sync_play_input(
        &self,
        account_epoch: u64,
        input: SyncPlayInput,
    ) -> Result<SyncPlaySnapshot, CoreError> {
        let event = input.into_event()?;
        let stepped;
        let snapshot = {
            let mut state = self.lock_state();
            let installed = state
                .account
                .installed_mut(account_epoch)
                .ok_or(CoreError::AccountChanged)?;
            let ctx = self.sync_play_ctx(account_epoch, &installed.client);
            stepped = installed.syncplay.step_locked(&ctx, event);
            installed.syncplay.snapshot()
        };
        drop(stepped);
        Ok(snapshot)
    }
}

/// `/SyncPlay/List` as answered: the groups, or a 403 (no SyncPlay access, docs/28 §2).
enum Listing {
    Groups(Vec<jellyfin_api::models::GroupInfoDto>),
    NoAccess,
}

impl JellybeamCore {
    /// docs/28 §2: lists the groups on `client`'s account, unlocked. 401 is Unauthorized, like any
    /// other call's (docs/02 §1); any other failure is fixed text, since a Status embeds the server
    /// body (§3.6). docs/02 §3: `account_epoch` is checked before the answer maps, so a replaced
    /// account's 401 never reaches Kotlin.
    fn list_groups_blocking(
        &self,
        account_epoch: u64,
        client: &JellyfinClient,
    ) -> Result<Listing, CoreError> {
        let listing = self.runtime().block_on(client.sync_play_list_groups());
        self.read_if_epoch(account_epoch, |_| ())
            .ok_or(CoreError::AccountChanged)?;
        match listing {
            Ok(groups) => Ok(Listing::Groups(groups)),
            Err(ApiError::Status { code: 403, .. }) => Ok(Listing::NoAccess),
            Err(e @ ApiError::Unauthorized { .. }) => Err(e.into()),
            Err(e) => {
                let detail = match error_class(&e) {
                    (class, 0) => class.to_string(),
                    (class, code) => format!("{class} {code}"),
                };
                Err(CoreError::Api {
                    detail: format!("watch together list failed: {detail}"),
                })
            }
        }
    }
}

/// docs/28 §4: receives one update per group step that changed something.
#[uniffi::export(with_foreign)]
pub trait SyncPlayListener: Send + Sync {
    /// Called on a runtime thread: Kotlin hops off before calling any export.
    fn on_step(&self, update: SyncPlayUpdate) -> Result<(), CallbackError>;
}

#[derive(uniffi::Record, Clone, PartialEq, Eq)]
pub struct SyncPlaySnapshot {
    pub group: SyncPlayGroup,
}

/// docs/28 §1: `name` is ours while creating, `None` while joining; `participants` are user names
/// in server order.
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum SyncPlayGroup {
    Idle,
    Starting {
        name: Option<String>,
    },
    Member {
        role: SyncPlayRole,
        stage: SyncPlayStage,
        host: SyncPlayHost,
        participants: Vec<String>,
        phase: SyncPlayPhase,
        connected: bool,
        prompt_shown: bool,
    },
    Leaving {
        reason: SyncPlayLeaveReason,
    },
}

/// docs/28 §1: `Lobby` before a title, `Playing` while one is loaded, `Finished` after the queue
/// ended.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPlayStage {
    Lobby,
    Playing,
    Finished,
}

/// docs/28 §1: who hosts, fixed at entry: us, the one member listed before us (`name`, verbatim),
/// one of several (unnamed), or nobody listed.
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum SyncPlayHost {
    Me,
    Known { name: String },
    Among,
    Unknown,
}

/// docs/28 §3.1: the server's group list; `NoAccess` is a 403 (no SyncPlay access).
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum SyncPlayRooms {
    Listed { rooms: Vec<SyncPlayRoom> },
    NoAccess,
}

/// One listed group: `name` and `participants` verbatim (a missing name is ""); `phase` is
/// `None` when the server's state is absent or unrecognized.
#[derive(uniffi::Record, Clone, PartialEq, Eq)]
pub struct SyncPlayRoom {
    pub group_id: String,
    pub name: String,
    pub participants: Vec<String>,
    pub phase: Option<SyncPlayPhase>,
}

/// docs/28 §4: ids are canonical hyphenated; a malformed one is refused before any step.
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum SyncPlayInput {
    ItemLoaded {
        playlist_item_id: String,
        landed_ticks: i64,
    },
    SeekLanded {
        seq: u64,
        landed_ticks: i64,
    },
    BufferingStarted,
    BufferingEnded {
        position_ticks: i64,
    },
    PlaybackError {
        severity: SyncPlaySeverity,
    },
    PlayerEnded,
    PlayerClosed,
    PositionSample {
        position_ticks: i64,
    },
    Leave,
    Pause,
    Unpause,
    Seek {
        ticks: i64,
    },
    PickItem {
        item_ids: Vec<String>,
        index: i32,
        start_ticks: i64,
    },
    Queue {
        item_ids: Vec<String>,
        mode: SyncPlayQueueMode,
    },
    Next,
    Prev,
}

/// One step's output for the executor: directives in seq order (it skips a seq at or below the
/// last it applied), notices, and whether the snapshot changed.
#[derive(uniffi::Record, Clone, Debug, Default, PartialEq, Eq)]
pub struct SyncPlayUpdate {
    pub directives: Vec<SyncPlayDirectiveAt>,
    pub notices: Vec<SyncPlayNotice>,
    pub changed: bool,
}

impl SyncPlayUpdate {
    fn is_empty(&self) -> bool {
        self.directives.is_empty() && self.notices.is_empty() && !self.changed
    }
}

#[derive(uniffi::Record, Clone, Debug, PartialEq, Eq)]
pub struct SyncPlayDirectiveAt {
    pub seq: u64,
    pub directive: SyncPlayDirective,
}

/// docs/28 §5: every position is an exact target; `due_wall_ms` is this device's wall clock.
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum SyncPlayDirective {
    Load {
        item_id: String,
        playlist_item_id: String,
        start_ticks: i64,
    },
    Pause {
        freeze_ticks: Option<i64>,
        due_wall_ms: i64,
    },
    Unpause {
        position_ticks: i64,
        due_wall_ms: i64,
    },
    Seek {
        position_ticks: i64,
    },
    Correct {
        position_ticks: i64,
    },
    HoldFor {
        ms: u64,
    },
    Detach,
    ReturnToLobby,
}

/// `name` is a user name, shown verbatim.
#[derive(uniffi::Enum, Clone, PartialEq, Eq)]
pub enum SyncPlayNotice {
    CreateRefused,
    JoinRefused,
    StartFailed,
    NoSuchGroup,
    NoAccess,
    Joined,
    UserJoined { name: String },
    UserLeft { name: String },
    GroupEnded { cause: SyncPlayEndCause },
    Reconnecting,
    HostLeft,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPlayRole {
    Host,
    Guest,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPlayPhase {
    Idle,
    Waiting,
    Paused,
    Playing,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPlayLeaveReason {
    User,
    PlayerError,
    HostLeft,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPlayEndCause {
    Removed,
    Gone,
    Auth,
    Lost,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPlaySeverity {
    Recoverable,
    Fatal,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPlayQueueMode {
    Queue,
    QueueNext,
}

impl From<Snapshot> for SyncPlaySnapshot {
    fn from(snapshot: Snapshot) -> Self {
        let group = match snapshot {
            Snapshot::Idle => SyncPlayGroup::Idle,
            Snapshot::Starting { name } => SyncPlayGroup::Starting {
                name: name.map(|n| n.0),
            },
            Snapshot::Member(m) => SyncPlayGroup::Member {
                role: m.role.into(),
                stage: m.stage.into(),
                host: m.host.into(),
                participants: m.participants.into_iter().map(|n| n.0).collect(),
                phase: m.phase.into(),
                connected: m.connected,
                prompt_shown: m.prompt_shown,
            },
            Snapshot::Leaving { reason } => SyncPlayGroup::Leaving {
                reason: reason.into(),
            },
        };
        Self { group }
    }
}

impl From<sp::Directive> for SyncPlayDirective {
    fn from(directive: sp::Directive) -> Self {
        match directive {
            sp::Directive::Load { entry, start_ticks } => Self::Load {
                item_id: entry.item_id.to_string(),
                playlist_item_id: entry.pid.to_string(),
                start_ticks,
            },
            sp::Directive::Pause(at) => Self::Pause {
                freeze_ticks: at.freeze_ticks,
                due_wall_ms: at.due_wall_ms,
            },
            sp::Directive::Unpause(at) => Self::Unpause {
                position_ticks: at.position_ticks,
                due_wall_ms: at.due_wall_ms,
            },
            sp::Directive::Seek { position_ticks } => Self::Seek { position_ticks },
            sp::Directive::Correct { position_ticks } => Self::Correct { position_ticks },
            sp::Directive::HoldFor { ms } => Self::HoldFor { ms },
            sp::Directive::Detach => Self::Detach,
            sp::Directive::ReturnToLobby => Self::ReturnToLobby,
        }
    }
}

impl From<sp::Notice> for SyncPlayNotice {
    fn from(notice: sp::Notice) -> Self {
        match notice {
            sp::Notice::CreateRefused => Self::CreateRefused,
            sp::Notice::JoinRefused => Self::JoinRefused,
            sp::Notice::StartFailed => Self::StartFailed,
            sp::Notice::NoSuchGroup => Self::NoSuchGroup,
            sp::Notice::NoAccess => Self::NoAccess,
            sp::Notice::Joined => Self::Joined,
            sp::Notice::UserJoined(name) => Self::UserJoined { name: name.0 },
            sp::Notice::UserLeft(name) => Self::UserLeft { name: name.0 },
            sp::Notice::GroupEnded(cause) => Self::GroupEnded {
                cause: cause.into(),
            },
            sp::Notice::Reconnecting => Self::Reconnecting,
            sp::Notice::HostLeft => Self::HostLeft,
        }
    }
}

impl From<sp::Role> for SyncPlayRole {
    fn from(role: sp::Role) -> Self {
        match role {
            sp::Role::Host => Self::Host,
            sp::Role::Guest => Self::Guest,
        }
    }
}

impl From<sp::Stage> for SyncPlayStage {
    fn from(stage: sp::Stage) -> Self {
        match stage {
            sp::Stage::Lobby => Self::Lobby,
            sp::Stage::Playing => Self::Playing,
            sp::Stage::Finished => Self::Finished,
        }
    }
}

/// `Among` carries no names: Kotlin shows no host name for several candidates.
impl From<sp::HostSeat> for SyncPlayHost {
    fn from(seat: sp::HostSeat) -> Self {
        match seat {
            sp::HostSeat::Me => Self::Me,
            sp::HostSeat::Known(name) => Self::Known { name: name.0 },
            sp::HostSeat::Among(_) => Self::Among,
            sp::HostSeat::Unknown => Self::Unknown,
        }
    }
}

impl From<sp::GroupPhase> for SyncPlayPhase {
    fn from(phase: sp::GroupPhase) -> Self {
        match phase {
            sp::GroupPhase::Idle => Self::Idle,
            sp::GroupPhase::Waiting => Self::Waiting,
            sp::GroupPhase::Paused => Self::Paused,
            sp::GroupPhase::Playing => Self::Playing,
        }
    }
}

impl From<sp::LeaveReason> for SyncPlayLeaveReason {
    fn from(reason: sp::LeaveReason) -> Self {
        match reason {
            sp::LeaveReason::User => Self::User,
            sp::LeaveReason::PlayerError => Self::PlayerError,
            sp::LeaveReason::HostLeft => Self::HostLeft,
        }
    }
}

impl From<sp::EndCause> for SyncPlayEndCause {
    fn from(cause: sp::EndCause) -> Self {
        match cause {
            sp::EndCause::Removed => Self::Removed,
            sp::EndCause::Gone => Self::Gone,
            sp::EndCause::Auth => Self::Auth,
            sp::EndCause::Lost => Self::Lost,
        }
    }
}

impl From<SyncPlaySeverity> for sp::Severity {
    fn from(severity: SyncPlaySeverity) -> Self {
        match severity {
            SyncPlaySeverity::Recoverable => Self::Recoverable,
            SyncPlaySeverity::Fatal => Self::Fatal,
        }
    }
}

impl From<SyncPlayQueueMode> for jellyfin_api::models::GroupQueueMode {
    fn from(mode: SyncPlayQueueMode) -> Self {
        match mode {
            SyncPlayQueueMode::Queue => Self::Queue,
            SyncPlayQueueMode::QueueNext => Self::QueueNext,
        }
    }
}

impl SyncPlayInput {
    fn into_event(self) -> Result<Event, CoreError> {
        Ok(match self {
            Self::PickItem {
                ref item_ids,
                index,
                ..
            } if usize::try_from(index).map_or(true, |i| i >= item_ids.len()) => {
                return Err(queue_refused());
            }
            Self::Queue { ref item_ids, .. } if item_ids.is_empty() => return Err(queue_refused()),
            Self::ItemLoaded {
                playlist_item_id,
                landed_ticks,
            } => Event::ItemLoaded {
                pid: parse_id(&playlist_item_id)?,
                landed_ticks,
            },
            Self::SeekLanded { seq, landed_ticks } => Event::SeekLanded {
                seq: Tag(seq),
                landed_ticks,
            },
            Self::BufferingStarted => Event::BufferingStarted,
            Self::BufferingEnded { position_ticks } => Event::BufferingEnded { position_ticks },
            Self::PlaybackError { severity } => Event::PlaybackError(severity.into()),
            Self::PlayerEnded => Event::PlayerEnded,
            Self::PlayerClosed => Event::PlayerClosed,
            Self::PositionSample { position_ticks } => Event::PositionSample { position_ticks },
            Self::Leave => Event::Leave,
            Self::Pause => Event::Pause,
            Self::Unpause => Event::Unpause,
            Self::Seek { ticks } => Event::Seek { ticks },
            Self::PickItem {
                item_ids,
                index,
                start_ticks,
            } => Event::PickItem(sp::NewQueue {
                items: parse_ids(&item_ids)?,
                index,
                start_ticks,
            }),
            Self::Queue { item_ids, mode } => Event::Queue(sp::AddToQueue {
                items: parse_ids(&item_ids)?,
                mode: mode.into(),
            }),
            Self::Next => Event::Next,
            Self::Prev => Event::Prev,
        })
    }
}

/// A malformed id crosses as fixed text, never echoing the input.
fn parse_id(id: &str) -> Result<Uuid, CoreError> {
    Uuid::parse_str(id).map_err(|_| CoreError::Api {
        detail: "watch together: malformed id".to_string(),
    })
}

fn parse_ids(ids: &[String]) -> Result<Vec<Uuid>, CoreError> {
    ids.iter().map(|id| parse_id(id)).collect()
}

/// docs/28 §4: a pick or queue the server can't play (no items, or a start outside them).
fn queue_refused() -> CoreError {
    CoreError::Api {
        detail: "watch together: empty queue or index outside it".to_string(),
    }
}

/// docs/28 §3.6: types that carry a name or id name only their variant in `Debug`.
impl std::fmt::Debug for SyncPlaySnapshot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SyncPlaySnapshot")
            .field("group", &self.group)
            .finish()
    }
}

impl std::fmt::Debug for SyncPlayGroup {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Idle => "Idle",
            Self::Starting { .. } => "Starting",
            Self::Member { .. } => "Member",
            Self::Leaving { .. } => "Leaving",
        })
    }
}

impl std::fmt::Debug for SyncPlayHost {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Me => "Me",
            Self::Known { .. } => "Known",
            Self::Among => "Among",
            Self::Unknown => "Unknown",
        })
    }
}

impl std::fmt::Debug for SyncPlayRooms {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Listed { rooms } => write!(f, "Listed({})", rooms.len()),
            Self::NoAccess => f.write_str("NoAccess"),
        }
    }
}

impl std::fmt::Debug for SyncPlayRoom {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "SyncPlayRoom({})", self.participants.len())
    }
}

impl std::fmt::Debug for SyncPlayDirective {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Load { .. } => "Load",
            Self::Pause { .. } => "Pause",
            Self::Unpause { .. } => "Unpause",
            Self::Seek { .. } => "Seek",
            Self::Correct { .. } => "Correct",
            Self::HoldFor { .. } => "HoldFor",
            Self::Detach => "Detach",
            Self::ReturnToLobby => "ReturnToLobby",
        })
    }
}

impl std::fmt::Debug for SyncPlayInput {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::ItemLoaded { .. } => "ItemLoaded",
            Self::SeekLanded { .. } => "SeekLanded",
            Self::BufferingStarted => "BufferingStarted",
            Self::BufferingEnded { .. } => "BufferingEnded",
            Self::PlaybackError { .. } => "PlaybackError",
            Self::PlayerEnded => "PlayerEnded",
            Self::PlayerClosed => "PlayerClosed",
            Self::PositionSample { .. } => "PositionSample",
            Self::Leave => "Leave",
            Self::Pause => "Pause",
            Self::Unpause => "Unpause",
            Self::Seek { .. } => "Seek",
            Self::PickItem { .. } => "PickItem",
            Self::Queue { .. } => "Queue",
            Self::Next => "Next",
            Self::Prev => "Prev",
        })
    }
}

impl std::fmt::Debug for SyncPlayNotice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::CreateRefused => f.write_str("CreateRefused"),
            Self::JoinRefused => f.write_str("JoinRefused"),
            Self::StartFailed => f.write_str("StartFailed"),
            Self::NoSuchGroup => f.write_str("NoSuchGroup"),
            Self::NoAccess => f.write_str("NoAccess"),
            Self::Joined => f.write_str("Joined"),
            Self::UserJoined { .. } => f.write_str("UserJoined"),
            Self::UserLeft { .. } => f.write_str("UserLeft"),
            Self::GroupEnded { cause } => write!(f, "GroupEnded({cause:?})"),
            Self::Reconnecting => f.write_str("Reconnecting"),
            Self::HostLeft => f.write_str("HostLeft"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::object::tests::{
        admit, core_signed_in_against_routes, install_relaying_bus, install_test_bus,
        playback_fixture, spawn_flooded_forwarder, take_bundle_leaving_forwarder,
        two_accounts_on_mocks, wait_until, wait_until_for, weak_state, Gate, RouteMockServer,
    };
    use crate::object::ChangeListener;
    use crate::object::FINAL_REPORT_SETTLE_BUDGET;
    use jellyfin_api::models::{
        GroupInfoDto, PlayQueueUpdate, PlayQueueUpdateReason, SyncPlayQueueItem,
    };
    use jellyfin_api::ServerEvent;
    use jellyfin_core::BusConnection;
    use jellyfin_core::BusEvent;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::sync::Mutex;

    const ROOM_NAME: &str = "member-a's group";
    const GROUP_A: &str = "00000000-0000-4000-8000-00000000000a";
    const GROUP_B: &str = "00000000-0000-4000-8000-00000000000b";
    const ITEM: &str = "00000000-0000-4000-8000-000000000101";
    const PID: &str = "00000000-0000-4000-8000-000000000102";

    fn uuid(id: &str) -> Uuid {
        Uuid::parse_str(id).expect("synthetic id")
    }

    /// Long enough for a request that is going out to arrive, for "it did not happen" asserts.
    fn settle() {
        std::thread::sleep(Duration::from_millis(100));
    }

    fn listener() -> Arc<dyn SyncPlayListener> {
        Arc::new(Recorder::default())
    }

    /// Records every update; once armed, throws on the next update carrying a directive, as a
    /// Kotlin callback might.
    #[derive(Default)]
    struct Recorder {
        updates: Mutex<Vec<SyncPlayUpdate>>,
        throw_next_directive: AtomicBool,
    }

    impl Recorder {
        fn arm_throw(&self) {
            self.throw_next_directive.store(true, Ordering::SeqCst);
        }

        fn updates(&self) -> Vec<SyncPlayUpdate> {
            self.updates
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        }

        fn notices(&self) -> Vec<SyncPlayNotice> {
            self.updates().into_iter().flat_map(|u| u.notices).collect()
        }

        fn directives(&self) -> Vec<SyncPlayDirectiveAt> {
            self.updates()
                .into_iter()
                .flat_map(|u| u.directives)
                .collect()
        }
    }

    impl SyncPlayListener for Recorder {
        fn on_step(&self, update: SyncPlayUpdate) -> Result<(), CallbackError> {
            let directive = !update.directives.is_empty();
            self.updates
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(update);
            if directive && self.throw_next_directive.swap(false, Ordering::SeqCst) {
                return Err(CallbackError::Failed);
            }
            Ok(())
        }
    }

    /// A signed-in core against a mock, with a test bus whose connection the test drives.
    struct Rig {
        _dir: tempfile::TempDir,
        core: Arc<JellybeamCore>,
        mock: RouteMockServer,
        bus: tokio::sync::watch::Sender<BusConnection>,
        epoch: u64,
    }

    impl Rig {
        fn up() -> Self {
            let (dir, core, mock) = core_signed_in_against_routes();
            mock.route("GET", "/SyncPlay/List", 200, serde_json::json!([]));
            let bus = connect_bus(&core);
            let epoch = core.account_epoch();
            Self {
                _dir: dir,
                core,
                mock,
                bus,
                epoch,
            }
        }

        fn feed(&self, event: Event) -> bool {
            let stepped = {
                let _rt = self.core.runtime().enter();
                sync_play_feed(&weak_state(&self.core), self.epoch, event)
            };
            self.assert_exec_follows_group();
            stepped
        }

        fn snapshot(&self) -> SyncPlayGroup {
            self.core
                .sync_play_snapshot(self.epoch)
                .expect("the account is installed")
                .group
        }

        fn input(&self, input: SyncPlayInput) {
            self.core
                .sync_play_input(self.epoch, input)
                .expect("the account is installed");
            self.assert_exec_follows_group();
        }

        /// The group's Exec kind, for the Exec-follows-state invariant.
        fn exec(&self) -> &'static str {
            self.group_and_exec().1
        }

        /// The snapshot and the Exec kind, read under one lock so no async step falls between.
        fn group_and_exec(&self) -> (SyncPlayGroup, &'static str) {
            let state = self.core.lock_state();
            let owner = &state
                .account
                .installed()
                .expect("an account is installed")
                .syncplay;
            let exec = match owner.exec {
                Exec::None => "None",
                Exec::Live { .. } => "Live",
                Exec::Draining { .. } => "Draining",
            };
            (owner.snapshot().group, exec)
        }

        /// docs/28 §4: None while Idle, Live while starting or a member, Draining while leaving.
        fn assert_exec_follows_group(&self) {
            let (group, exec) = self.group_and_exec();
            let want = match group {
                SyncPlayGroup::Idle => "None",
                SyncPlayGroup::Starting { .. } | SyncPlayGroup::Member { .. } => "Live",
                SyncPlayGroup::Leaving { .. } => "Draining",
            };
            assert_eq!(exec, want, "Exec follows the group ({group:?})");
        }

        fn hits(&self, method: &str, path: &str) -> usize {
            self.mock.hit_count(method, path)
        }

        fn wait_entry_answered(&self) {
            wait_entry_answered(&self.core);
        }

        /// Creates a group and feeds its GroupJoined: a host member of `GROUP_A`.
        fn member(&self, listener: Arc<Recorder>) {
            let created = self
                .core
                .sync_play_create(self.epoch, ROOM_NAME.into(), listener)
                .expect("the account is installed");
            let SyncPlayGroup::Starting { name: Some(name) } = created.group else {
                panic!("create starts a group, got {:?}", created.group);
            };
            self.wait_entry_answered();
            assert!(self.feed(group_joined(GROUP_A, Some(&name), &["member-a"])));
            assert!(matches!(self.snapshot(), SyncPlayGroup::Member { .. }));
            assert_eq!(self.exec(), "Live");
        }

        /// [`Self::member`] with a synced clock and a title loading, so player presses count.
        fn playing(&self, listener: Arc<Recorder>) {
            self.mock.route("GET", "/GetUtcTime", 200, utc_time_json());
            self.member(listener.clone());
            self.feed(update(Some(GROUP_A), one_item_queue()));
            wait_until(
                || {
                    listener
                        .directives()
                        .iter()
                        .any(|d| matches!(d.directive, SyncPlayDirective::Load { .. }))
                },
                "the title loads once the clock syncs",
            );
        }
    }

    /// Waits until the installed group's New or Join has its answer, so a later Leave goes out once.
    fn wait_entry_answered(core: &JellybeamCore) {
        wait_until(
            || {
                let epoch = core.account_epoch();
                let mut state = core.lock_state();
                let installed = state.account.installed_mut(epoch);
                match &mut installed.expect("installed").syncplay.exec {
                    Exec::Live { jobs, .. } => jobs.entry.as_mut().is_none_or(|entry| {
                        !matches!(entry.try_recv(), Err(oneshot::error::TryRecvError::Empty))
                    }),
                    _ => true,
                }
            },
            "the entry request is answered",
        );
    }

    /// Puts a connected test bus on the installed account; the sender drives its connection.
    fn connect_bus(core: &JellybeamCore) -> tokio::sync::watch::Sender<BusConnection> {
        let (bundle_tx, _) = tokio::sync::broadcast::channel(16);
        let bus = install_test_bus(core, bundle_tx, None);
        bus.send_replace(BusConnection::Connected);
        bus
    }

    /// A new one-title queue, `ITEM` as `PID`, newer than anything applied before.
    fn one_item_queue() -> GroupUpdate {
        GroupUpdate::PlayQueue(PlayQueueUpdate {
            playlist: vec![SyncPlayQueueItem {
                item_id: Some(uuid(ITEM)),
                playlist_item_id: Some(uuid(PID)),
            }],
            playing_item_index: Some(0),
            last_update: chrono::DateTime::from_timestamp_millis(wall_ms()),
            reason: Some(PlayQueueUpdateReason::NewPlaylist),
            ..Default::default()
        })
    }

    fn update(group: Option<&str>, update: GroupUpdate) -> Event {
        Event::Server(SyncPlayEvent::GroupUpdate {
            group_id: group.map(uuid),
            update,
        })
    }

    fn group_joined(group: &str, name: Option<&str>, participants: &[&str]) -> Event {
        update(
            Some(group),
            GroupUpdate::GroupJoined(GroupInfoDto {
                group_id: Some(uuid(group)),
                group_name: name.map(str::to_string),
                participants: participants.iter().map(|p| (*p).to_string()).collect(),
                ..Default::default()
            }),
        )
    }

    fn utc_time_json() -> serde_json::Value {
        serde_json::json!({
            "RequestReceptionTime": "2026-01-01T00:00:00.000Z",
            "ResponseTransmissionTime": "2026-01-01T00:00:00.001Z"
        })
    }

    /// docs/28 §8: a fresh account's group is Idle with no scope, task or listener.
    #[test]
    fn a_fresh_account_holds_an_idle_group_without_scope_or_listener() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        let epoch = core.account_epoch();
        let snapshot = core.sync_play_snapshot(epoch).expect("installed");
        assert_eq!(snapshot.group, SyncPlayGroup::Idle);
        let state = core.lock_state();
        let installed = state.account.installed().expect("installed");
        assert!(matches!(installed.syncplay.exec, Exec::None));
        drop(state);
        assert_eq!(mock.hit_count_containing("POST", "/SyncPlay/"), 0);
    }

    /// docs/28 §3.5, §8: outside a group, connects and frames send nothing and start nothing.
    #[test]
    fn an_idle_account_sends_no_syncplay_request_across_connects_and_frames() {
        let rig = Rig::up();
        let frames = [
            Event::BusConnected,
            Event::BusDisconnected,
            Event::BusConnected,
            update(Some(GROUP_A), GroupUpdate::UserJoined("member-b".into())),
            update(None, GroupUpdate::NotInGroup(String::new())),
            update(None, GroupUpdate::GroupDoesNotExist(String::new())),
            update(Some(GROUP_A), GroupUpdate::GroupLeft(GROUP_A.into())),
            Event::Timer(sp::Timer::SnapshotWindow { token: Tag(1) }),
            Event::BusUnauthorized,
        ];
        for frame in frames {
            rig.feed(frame);
            assert_eq!(rig.exec(), "None", "Idle starts no scope");
        }
        assert_eq!(rig.mock.hit_count_containing("POST", "/SyncPlay/"), 0);
        assert_eq!(rig.mock.hit_count_containing("GET", "/SyncPlay"), 0);
        assert_eq!(rig.hits("GET", "/GetUtcTime"), 0);
    }

    /// Counts change events and reauthorization asks apart.
    #[derive(Default)]
    struct ChangeCounter {
        changes: AtomicUsize,
        unauthorized: AtomicUsize,
    }

    impl ChangeCounter {
        fn changes(&self) -> usize {
            self.changes.load(Ordering::SeqCst)
        }

        fn unauthorized(&self) -> usize {
            self.unauthorized.load(Ordering::SeqCst)
        }
    }

    impl ChangeListener for ChangeCounter {
        fn on_change(&self, _event: crate::types::ChangeEvent) -> Result<(), CallbackError> {
            self.changes.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }

        fn on_unauthorized(
            &self,
            _account: crate::types::AccountIdentity,
        ) -> Result<(), CallbackError> {
            self.unauthorized.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }
    }

    /// docs/28 §8: a whole group's life, its frames sent through the real forwarder, reaches its own
    /// listener only: between a library marker before the frames and one after, the one change
    /// event is the second marker's.
    #[test]
    fn no_change_event_comes_from_any_syncplay_frame() {
        let (_dir, core, mock) = core_signed_in_against_routes();
        let bus = install_relaying_bus(&core, BusConnection::Connected);
        mock.route_with_query("GET", "/Items", "ids=", 200, &item_json());
        let epoch = core.account_epoch();
        let changes = Arc::new(ChangeCounter::default());
        core.set_change_listener(changes.clone());
        let relay = |event: BusEvent| bus.real_tx.send(event).expect("the forwarder listens");
        // The first marker adds the item and the second removes it, so each changes the mirror.
        let marker = |added: Vec<String>, removed: Vec<String>| {
            BusEvent::Server(ServerEvent::LibraryChanged {
                added,
                updated: vec![],
                removed,
            })
        };
        relay(marker(vec![ITEM.into()], vec![]));
        wait_until(
            || changes.changes() > 0,
            "the first marker's change arrives",
        );
        wait_until(
            || {
                let before = changes.changes();
                std::thread::sleep(Duration::from_millis(50));
                changes.changes() == before
            },
            "the first marker's changes stop",
        );
        let baseline = changes.changes();
        mock.route("GET", "/SyncPlay/List", 200, serde_json::json!([]));
        let recorder = Arc::new(Recorder::default());
        let created = core
            .sync_play_create(epoch, ROOM_NAME.into(), recorder.clone())
            .expect("installed");
        let SyncPlayGroup::Starting { name: Some(name) } = created.group else {
            panic!("create starts a group, got {:?}", created.group);
        };
        let frames = [
            group_joined(GROUP_A, Some(&name), &["member-a"]),
            update(Some(GROUP_A), GroupUpdate::UserJoined("member-b".into())),
            update(Some(GROUP_A), GroupUpdate::UserLeft("member-b".into())),
            update(Some(GROUP_A), GroupUpdate::GroupLeft(GROUP_A.into())),
        ];
        for frame in frames {
            let Event::Server(frame) = frame else {
                panic!("a server frame")
            };
            relay(BusEvent::Server(ServerEvent::SyncPlay(frame)));
        }
        relay(marker(vec![], vec![ITEM.into()]));
        wait_until(
            || changes.changes() > baseline,
            "the second marker's change arrives",
        );
        wait_until(
            || {
                recorder.notices().contains(&SyncPlayNotice::GroupEnded {
                    cause: SyncPlayEndCause::Removed,
                })
            },
            "the group's end reaches its listener",
        );
        assert!(recorder.notices().contains(&SyncPlayNotice::Joined));
        settle();
        assert_eq!(changes.changes(), baseline + 1);
        assert_eq!(
            core.sync_play_snapshot(epoch).expect("installed").group,
            SyncPlayGroup::Idle
        );
    }

    fn item_json() -> serde_json::Value {
        serde_json::json!({
            "Items": [{"Id": ITEM, "Name": "Sample Movie", "Type": "Movie"}],
            "TotalRecordCount": 1
        })
    }

    /// docs/28 §4: a bus lag may have dropped a group frame, so a member rejoins once; an Idle
    /// account's lag sends nothing.
    #[test]
    fn a_bus_lag_rejoins_the_group() {
        for member in [true, false] {
            let rig = Rig::up();
            if member {
                rig.member(Arc::new(Recorder::default()));
            }
            let flooded = spawn_flooded_forwarder(&rig.core, 300);
            rig.core
                .runtime()
                .block_on(flooded)
                .expect("the forwarder drains the flood and exits");
            if member {
                wait_until(
                    || rig.hits("POST", "/SyncPlay/Join") == 1,
                    "the lag rejoins",
                );
            }
            std::thread::sleep(Duration::from_millis(50));
            let expected = usize::from(member);
            assert_eq!(
                rig.hits("POST", "/SyncPlay/Join"),
                expected,
                "member {member}"
            );
            assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 0, "member {member}");
        }
    }

    /// docs/28 §4: a re-opened mirror takes the bus a group follows: a lobby start fails, and a
    /// member waits for the new bus's connect, then for its server refresh, then the 2 s window,
    /// before it rejoins.
    #[test]
    fn a_bus_swap_fails_a_lobby_start_and_rejoins_a_member_after_the_window() {
        let rig = Rig::up();
        let recorder = Arc::new(Recorder::default());
        rig.core
            .sync_play_create(rig.epoch, ROOM_NAME.into(), recorder.clone())
            .expect("installed");
        rig.core.open_mirror().expect("re-opens the mirror");
        assert_eq!(rig.snapshot(), SyncPlayGroup::Idle, "the start fails");
        assert_eq!(rig.exec(), "None");
        wait_until(
            || recorder.notices().contains(&SyncPlayNotice::StartFailed),
            "the failed start reaches the listener",
        );

        let relay = install_relaying_bus(&rig.core, BusConnection::Connected);
        let member = Arc::new(Recorder::default());
        rig.member(member.clone());
        // A connect's reconcile is no lag: relayed in order before a notice, it rejoins nothing.
        let joined = update(Some(GROUP_A), GroupUpdate::UserJoined("member-b".into()));
        let Event::Server(joined) = joined else {
            panic!("a server frame")
        };
        for event in [
            BusEvent::NeedsReconcile,
            BusEvent::Server(ServerEvent::SyncPlay(joined)),
        ] {
            relay.real_tx.send(event).expect("the forwarder listens");
        }
        wait_until(
            || {
                member
                    .notices()
                    .iter()
                    .any(|n| matches!(n, SyncPlayNotice::UserJoined { .. }))
            },
            "the frame after the reconcile is stepped",
        );
        assert_eq!(rig.hits("POST", "/SyncPlay/Join"), 0, "no rejoin");
        rig.core.open_mirror().expect("re-opens the mirror");
        assert!(
            matches!(
                rig.snapshot(),
                SyncPlayGroup::Member {
                    connected: false,
                    ..
                }
            ),
            "the member's link is down"
        );
        let bus = install_relaying_bus(&rig.core, BusConnection::Disconnected);
        let refreshes = rig.hits("GET", "/System/Info/Public");
        let held = rig.mock.gate("GET", "/System/Info/Public", refreshes);
        bus.connection.send_replace(BusConnection::Connected);
        wait_until(
            || rig.hits("GET", "/System/Info/Public") > refreshes,
            "the connect refreshes the server first",
        );
        std::thread::sleep(scaled(10_000));
        assert_eq!(
            rig.hits("POST", "/SyncPlay/Join"),
            0,
            "no window runs before the refresh returns"
        );
        held.open();
        wait_until(
            || rig.hits("POST", "/SyncPlay/Join") == 1,
            "the window ends in one rejoin",
        );
    }

    /// docs/28 §8: the listener is released once Idle is delivered.
    #[test]
    fn the_listener_is_released_when_the_group_returns_to_idle() {
        let rig = Rig::up();
        let recorder = Arc::new(Recorder::default());
        rig.member(recorder.clone());
        assert!(
            Arc::strong_count(&recorder) > 1,
            "the group holds its listener"
        );
        rig.input(SyncPlayInput::Leave);
        wait_until(
            || rig.snapshot() == SyncPlayGroup::Idle,
            "the Leave's answer ends the group",
        );
        assert_eq!(rig.exec(), "None");
        wait_until(
            || Arc::strong_count(&recorder) == 1,
            "the delivery task releases the listener",
        );
    }

    /// docs/28 §3.3: a create while anything plays, or with the bus down, is refused with a
    /// notice and sends nothing.
    #[test]
    fn create_while_playing_or_with_the_bus_down_is_refused_without_a_request() {
        for row in ["bus down", "playing"] {
            let rig = Rig::up();
            match row {
                "bus down" => {
                    rig.bus.send_replace(BusConnection::Disconnected);
                }
                _ => {
                    admit(&rig.core);
                }
            }
            let recorder = Arc::new(Recorder::default());
            let snapshot = rig
                .core
                .sync_play_create(rig.epoch, ROOM_NAME.into(), recorder.clone())
                .expect("installed");
            assert_eq!(snapshot.group, SyncPlayGroup::Idle, "{row}");
            assert_eq!(rig.exec(), "None", "{row}");
            wait_until(
                || recorder.notices() == vec![SyncPlayNotice::CreateRefused],
                "the refusal reaches the listener",
            );
            wait_until(
                || Arc::strong_count(&recorder) == 1,
                "a refused create keeps no listener",
            );
            assert_eq!(rig.hits("POST", "/SyncPlay/New"), 0, "{row}");
        }
    }

    /// Starts one movie on `rig` and stops it, its Stopped held at the server until the returned
    /// gate opens.
    fn stop_with_stopped_held(rig: &Rig) -> Gate {
        let (item_json, playback_info_json) = playback_fixture(
            "Test Movie",
            "ms-1",
            Some("mkv"),
            true,
            None,
            None,
            "play-session-create-gate",
        );
        rig.mock.route("GET", "/Items", 200, item_json);
        let playback_info = format!("/Items/{ITEM}/PlaybackInfo");
        rig.mock
            .route("POST", &playback_info, 200, playback_info_json);
        let stopped = rig.mock.gate("POST", "/Sessions/Playing/Stopped", 0);
        let plan = rig
            .core
            .prepare_playback_now(ITEM.to_string(), false)
            .expect("the movie plays");
        rig.core
            .report_position(plan.play_session_id.clone(), 30_000_000);
        rig.core.stop_playback(plan.play_session_id, 30_000_000);
        wait_until(
            || rig.hits("POST", "/Sessions/Playing/Stopped") >= 1,
            "the Stopped reaches the server",
        );
        stopped
    }

    /// docs/28 §3.3: the server seeds a new group from our now-playing, so a create right after a
    /// stop goes out only once that stop's Stopped has landed.
    #[test]
    fn create_waits_for_the_last_stop_to_settle() {
        let rig = Rig::up();
        let stopped = stop_with_stopped_held(&rig);

        let (core, epoch) = (rig.core.clone(), rig.epoch);
        let create =
            std::thread::spawn(move || core.sync_play_create(epoch, ROOM_NAME.into(), listener()));
        settle();
        assert_eq!(
            rig.hits("POST", "/SyncPlay/New"),
            0,
            "no group is created while the Stopped is in flight"
        );

        let opened = Instant::now();
        stopped.open();
        let created = create
            .join()
            .expect("create returns")
            .expect("the account is installed");
        assert!(
            matches!(created.group, SyncPlayGroup::Starting { .. }),
            "create starts a group once the stop settled, got {:?}",
            created.group
        );
        wait_until(
            || rig.hits("POST", "/SyncPlay/New") == 1,
            "the create goes out",
        );
        let new_at = rig.mock.arrivals("POST", "/SyncPlay/New")[0];
        assert!(new_at >= opened, "New arrived before the Stopped landed");
    }

    /// docs/28 §3.3: with no Stopped in flight, a create goes out at once.
    #[test]
    fn create_with_no_stop_in_flight_does_not_wait() {
        for row in ["no stop", "a settled stop"] {
            let rig = Rig::up();
            if row == "a settled stop" {
                stop_with_stopped_held(&rig).open();
                settle();
            }
            let started = Instant::now();
            rig.core
                .sync_play_create(rig.epoch, ROOM_NAME.into(), listener())
                .expect("the account is installed");
            wait_until(
                || rig.hits("POST", "/SyncPlay/New") == 1,
                "the create goes out",
            );
            assert!(
                started.elapsed() < Duration::from_millis(500),
                "{row}: create waited {:?}",
                started.elapsed()
            );
        }
    }

    /// docs/28 §3.3: a Stopped that never lands holds a create for the settle budget, no longer.
    #[test]
    fn a_create_waits_at_most_the_settle_budget_for_a_stop_that_never_settles() {
        let rig = Rig::up();
        let stopped = stop_with_stopped_held(&rig);

        let started = Instant::now();
        rig.core
            .sync_play_create(rig.epoch, ROOM_NAME.into(), listener())
            .expect("the account is installed");
        let waited = started.elapsed();
        assert!(
            waited >= FINAL_REPORT_SETTLE_BUDGET,
            "create went out after {waited:?}, before the budget"
        );
        assert!(
            waited < FINAL_REPORT_SETTLE_BUDGET + Duration::from_secs(1),
            "create waited {waited:?}, past the budget"
        );
        wait_until(
            || rig.hits("POST", "/SyncPlay/New") == 1,
            "the create goes out",
        );
        stopped.open();
    }

    /// docs/28 §5: one sample at once, three more a second apart, then a minute; each sample pings.
    #[test]
    fn group_joined_starts_time_sync_once_a_second_then_pings_each_sample() {
        let rig = Rig::up();
        rig.mock.route("GET", "/GetUtcTime", 200, utc_time_json());
        rig.member(Arc::new(Recorder::default()));
        let samples = || rig.hits("GET", "/GetUtcTime");
        wait_until(|| samples() >= 1, "the first sample goes out at once");
        wait_until(|| samples() >= 4, "three more samples follow");
        let at = rig.mock.arrivals("GET", "/GetUtcTime");
        assert!(
            at[3].duration_since(at[0]) >= scaled(3_000),
            "three samples a second apart: {:?}",
            at[3].duration_since(at[0])
        );
        wait_until(
            || rig.hits("POST", "/SyncPlay/Ping") >= 4,
            "each sample pings",
        );
        std::thread::sleep(scaled(30_000));
        assert_eq!(samples(), 4, "the fifth sample waits a minute");
        assert_eq!(rig.hits("POST", "/SyncPlay/Ping"), 4);
    }

    /// docs/28 §5: a failing clock is retried after a second, never in a loop.
    #[test]
    fn a_failed_time_sample_retries_after_a_second_not_at_once() {
        let rig = Rig::up();
        rig.mock
            .route("GET", "/GetUtcTime", 500, serde_json::json!({}));
        rig.member(Arc::new(Recorder::default()));
        let samples = || rig.hits("GET", "/GetUtcTime");
        wait_until(|| samples() >= 2, "a failed sample is retried");
        let (retried, started) = (samples(), Instant::now());
        std::thread::sleep(scaled(5_000));
        let more = samples() - retried;
        let elapsed = started.elapsed();
        let bound = elapsed.as_millis() / scaled(1_000).as_millis() + 1;
        assert!(
            more as u128 <= bound,
            "at most one retry a second, got {more} in {elapsed:?}"
        );
        assert_eq!(rig.hits("POST", "/SyncPlay/Ping"), 0);
    }

    /// docs/28 §2: a 403 on a member call rejoins once; a 403 on
    /// that rejoin ends the group as Lost, with no loop and no account change.
    #[test]
    fn a_403_on_a_member_request_rejoins_and_a_403_on_the_rejoin_ends_the_group() {
        let rig = Rig::up();
        rig.mock
            .route("POST", "/SyncPlay/Pause", 403, serde_json::json!({}));
        rig.mock
            .route("POST", "/SyncPlay/Join", 403, serde_json::json!({}));
        let changes = Arc::new(ChangeCounter::default());
        rig.core.set_change_listener(changes.clone());
        let recorder = Arc::new(Recorder::default());
        rig.playing(recorder.clone());
        rig.input(SyncPlayInput::Pause);
        wait_until(
            || rig.snapshot() == SyncPlayGroup::Idle,
            "the failed rejoin ends the group",
        );
        assert_eq!(rig.hits("POST", "/SyncPlay/Join"), 1, "one rejoin");
        assert!(recorder.notices().contains(&SyncPlayNotice::GroupEnded {
            cause: SyncPlayEndCause::Lost
        }));
        assert_eq!(
            rig.core.account_epoch(),
            rig.epoch,
            "a 403 never moves the epoch"
        );
        settle();
        assert_eq!(rig.hits("POST", "/SyncPlay/Join"), 1, "no rejoin loop");
        assert_eq!(changes.unauthorized(), 0, "a 403 never asks to reauthorize");
    }

    /// docs/28 §5: member requests reach the server one at a time, in step order.
    #[test]
    fn member_requests_reach_the_server_in_step_order() {
        let rig = Rig::up();
        rig.playing(Arc::new(Recorder::default()));
        let held = rig.mock.gate("POST", "/SyncPlay/Pause", 0);
        rig.input(SyncPlayInput::Pause);
        rig.input(SyncPlayInput::Unpause);
        rig.input(SyncPlayInput::Seek { ticks: 50_000_000 });
        wait_until(
            || rig.hits("POST", "/SyncPlay/Pause") == 1,
            "the Pause arrives",
        );
        std::thread::sleep(Duration::from_millis(50));
        assert_eq!(
            rig.hits("POST", "/SyncPlay/Unpause"),
            0,
            "the Unpause waits its turn"
        );
        held.open();
        wait_until(
            || rig.hits("POST", "/SyncPlay/Seek") == 1,
            "the Seek arrives",
        );
        let log = rig.mock.log_snapshot();
        let at = |path: &str| {
            log.iter()
                .position(|entry| entry.starts_with(&format!("POST {path}")))
                .expect("arrived")
        };
        assert!(at("/SyncPlay/Pause") < at("/SyncPlay/Unpause"));
        assert!(at("/SyncPlay/Unpause") < at("/SyncPlay/Seek"));
    }

    /// docs/28 §5: directives arrive in seq order, and a throwing listener still gets
    /// every later update.
    #[test]
    fn directives_reach_the_listener_in_step_order_even_after_it_throws() {
        let rig = Rig::up();
        let recorder = Arc::new(Recorder::default());
        rig.playing(recorder.clone());
        recorder.arm_throw();
        for _ in 0..5 {
            rig.input(SyncPlayInput::Pause);
        }
        let pauses = || {
            recorder
                .directives()
                .iter()
                .filter(|d| matches!(d.directive, SyncPlayDirective::Pause { .. }))
                .count()
        };
        wait_until(|| pauses() == 5, "every Pause directive arrives");
        let seqs: Vec<u64> = recorder.directives().iter().map(|d| d.seq).collect();
        assert!(seqs.windows(2).all(|w| w[0] < w[1]), "seqs rise: {seqs:?}");
    }

    /// docs/02 §3: a call or result for a replaced account is AccountChanged or
    /// dropped, and steps nothing.
    #[test]
    fn a_stale_epoch_input_is_account_changed_and_steps_nothing() {
        let rig = Rig::up();
        let stale = rig.epoch + 1;
        assert!(matches!(
            rig.core.sync_play_snapshot(stale),
            Err(CoreError::AccountChanged)
        ));
        assert!(matches!(
            rig.core.sync_play_input(stale, SyncPlayInput::Pause),
            Err(CoreError::AccountChanged)
        ));
        assert!(matches!(
            rig.core.sync_play_room_name(stale),
            Err(CoreError::AccountChanged)
        ));
        assert!(matches!(
            rig.core
                .sync_play_create(stale, ROOM_NAME.into(), listener()),
            Err(CoreError::AccountChanged)
        ));
        assert!(matches!(
            rig.core
                .sync_play_join_room(stale, GROUP_A.into(), listener()),
            Err(CoreError::AccountChanged)
        ));
        assert!(matches!(
            rig.core.sync_play_list_rooms(stale),
            Err(CoreError::AccountChanged)
        ));
        let _rt = rig.core.runtime().enter();
        assert!(!sync_play_feed(
            &weak_state(&rig.core),
            stale,
            group_joined(GROUP_A, None, &[])
        ));
        drop(_rt);
        assert_eq!(rig.snapshot(), SyncPlayGroup::Idle);
        assert_eq!(rig.exec(), "None");
        assert_eq!(rig.mock.hit_count_containing("POST", "/SyncPlay/"), 0);
        assert_eq!(
            rig.mock.hit_count_containing("GET", "/SyncPlay"),
            0,
            "no list"
        );
    }

    fn list_json() -> serde_json::Value {
        serde_json::json!([
            {"GroupId": GROUP_A, "GroupName": "Room A", "Participants": []},
            {"GroupId": GROUP_B, "GroupName": "Room B", "Participants": []}
        ])
    }

    /// docs/28 §1: the room name is "{user}'s group", and with that name already listed the first
    /// free numeric suffix.
    #[test]
    fn room_name_is_the_users_group_with_a_suffix_when_listed() {
        for (listed, want) in [
            (vec![], "jellybeam-user's group"),
            (vec!["Room A"], "jellybeam-user's group"),
            (vec!["jellybeam-user's group"], "jellybeam-user's group 2"),
            (
                vec!["Jellybeam-User's Group ", "jellybeam-user's group 2"],
                "jellybeam-user's group 3",
            ),
        ] {
            let rig = Rig::up();
            let rows: Vec<_> = listed
                .iter()
                .map(
                    |n| serde_json::json!({"GroupId": GROUP_B, "GroupName": n, "Participants": []}),
                )
                .collect();
            rig.mock
                .route("GET", "/SyncPlay/List", 200, serde_json::Value::Array(rows));
            let name = rig.core.sync_play_room_name(rig.epoch).expect("listed");
            assert_eq!(name, want, "{listed:?}");
            assert_eq!(rig.hits("GET", "/SyncPlay/List"), 1);
            assert_eq!(rig.mock.hit_count_containing("POST", "/SyncPlay/"), 0);
            assert_eq!(rig.snapshot(), SyncPlayGroup::Idle);
        }
    }

    /// docs/28 §1: a create posts the name it is handed and never lists, so nothing it waits on
    /// can outlast its caller.
    #[test]
    fn create_posts_its_given_name_and_never_lists() {
        let rig = Rig::up();
        let created = rig
            .core
            .sync_play_create(rig.epoch, "member-b's group 2".into(), listener())
            .expect("installed");
        assert_eq!(
            created.group,
            SyncPlayGroup::Starting {
                name: Some("member-b's group 2".into())
            }
        );
        wait_until(
            || rig.hits("POST", "/SyncPlay/New") == 1,
            "the New goes out",
        );
        let bodies = rig.mock.request_bodies_containing("POST", "/SyncPlay/New");
        assert_eq!(
            bodies,
            vec![serde_json::json!({"GroupName": "member-b's group 2"})]
        );
        assert_eq!(rig.hits("GET", "/SyncPlay/List"), 0);
    }

    /// docs/28 §1, §2: a 403 list names the room plainly; any other failure is fixed text without
    /// the server's body; a 401 is the typed Unauthorized; none posts anything.
    #[test]
    fn room_name_with_a_failing_list_is_plain_or_an_error() {
        let rig = Rig::up();
        rig.mock
            .route("GET", "/SyncPlay/List", 403, serde_json::json!({}));
        assert_eq!(
            rig.core
                .sync_play_room_name(rig.epoch)
                .expect("a 403 names"),
            "jellybeam-user's group"
        );

        for (status, check) in [(500u16, "text"), (401, "unauthorized")] {
            let rig = Rig::up();
            rig.mock.route(
                "GET",
                "/SyncPlay/List",
                status,
                serde_json::json!({"Body": "secret-name"}),
            );
            match (rig.core.sync_play_room_name(rig.epoch), check) {
                (Err(CoreError::Api { detail }), "text") => {
                    assert_eq!(detail, "watch together list failed: http 500");
                }
                (Err(CoreError::Unauthorized { .. }), "unauthorized") => {}
                (other, _) => panic!("{status}: unexpected {other:?}"),
            }
            settle();
            assert_eq!(rig.hits("POST", "/SyncPlay/New"), 0, "{status}");
            assert_eq!(rig.exec(), "None", "{status}");
        }
        settle();
        assert_eq!(rig.hits("POST", "/SyncPlay/New"), 0, "403");
    }

    /// docs/02 §3: a room name answered after its account was replaced is AccountChanged, and no
    /// group is created on either account.
    #[test]
    fn room_name_for_a_moved_epoch_is_account_changed_and_creates_nothing() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();
        mock_a.route("GET", "/SyncPlay/List", 200, list_json());
        let held = mock_a.gate("GET", "/SyncPlay/List", 0);
        let epoch = core.account_epoch();
        let naming = {
            let core = core.clone();
            std::thread::spawn(move || core.sync_play_room_name(epoch))
        };
        wait_until(
            || mock_a.hit_count("GET", "/SyncPlay/List") == 1,
            "the list is in flight",
        );
        core.switch_session(0).expect("account B is saved");
        held.open();
        let name = naming.join().expect("the name returns");
        assert!(matches!(name, Err(CoreError::AccountChanged)), "{name:?}");
        settle();
        assert_eq!(mock_a.hit_count("POST", "/SyncPlay/New"), 0);
        assert_eq!(mock_b.hit_count("POST", "/SyncPlay/New"), 0);
        assert_eq!(mock_b.hit_count("GET", "/SyncPlay/List"), 0);
    }

    const GROUP_C: &str = "00000000-0000-4000-8000-00000000000c";

    /// docs/28 §3.1: the room list is the server's list as it stands, names verbatim, steps nothing.
    #[test]
    fn rooms_list_maps_listed_groups_verbatim_and_drops_idless() {
        let rig = Rig::up();
        rig.mock.route(
            "GET",
            "/SyncPlay/List",
            200,
            serde_json::json!([
                {"GroupId": GROUP_A, "GroupName": " room One ", "State": "Playing",
                 "Participants": ["member-b", "member-a"]},
                {"GroupName": "no-id", "State": "Idle", "Participants": ["member-x"]},
                {"GroupId": GROUP_B, "Participants": ["member-c"]},
                {"GroupId": GROUP_C, "GroupName": "XYZ789", "State": "Lingering",
                 "Participants": []}
            ]),
        );
        let rooms = rig.core.sync_play_list_rooms(rig.epoch).expect("listed");
        let room = |id: &str, name: &str, members: &[&str], phase| SyncPlayRoom {
            group_id: id.into(),
            name: name.into(),
            participants: members.iter().map(|m| (*m).to_string()).collect(),
            phase,
        };
        assert_eq!(
            rooms,
            SyncPlayRooms::Listed {
                rooms: vec![
                    room(
                        GROUP_A,
                        " room One ",
                        &["member-b", "member-a"],
                        Some(SyncPlayPhase::Playing)
                    ),
                    room(GROUP_B, "", &["member-c"], None),
                    room(GROUP_C, "XYZ789", &[], None),
                ]
            }
        );
        assert_eq!(rig.hits("GET", "/SyncPlay/List"), 1);
        assert_eq!(rig.mock.hit_count_containing("POST", "/SyncPlay/"), 0);
        assert_eq!(rig.snapshot(), SyncPlayGroup::Idle);
        assert_eq!(rig.exec(), "None");
    }

    /// docs/28 §2: a 403 list is no SyncPlay access, never a sign-out.
    #[test]
    fn rooms_403_is_no_access() {
        let rig = Rig::up();
        rig.mock.route(
            "GET",
            "/SyncPlay/List",
            403,
            serde_json::json!({"Body": "secret-name"}),
        );
        assert_eq!(
            rig.core.sync_play_list_rooms(rig.epoch).expect("answered"),
            SyncPlayRooms::NoAccess
        );
    }

    /// docs/02 §1: a 401 list is the typed Unauthorized, so the account goes to reauthorization.
    #[test]
    fn rooms_401_is_unauthorized() {
        let rig = Rig::up();
        rig.mock
            .route("GET", "/SyncPlay/List", 401, serde_json::json!({}));
        let rooms = rig.core.sync_play_list_rooms(rig.epoch);
        assert!(
            matches!(rooms, Err(CoreError::Unauthorized { .. })),
            "{rooms:?}"
        );
    }

    /// docs/28 §3.6: any other failure is fixed text, never the server's body.
    #[test]
    fn rooms_500_is_fixed_text() {
        let rig = Rig::up();
        rig.mock.route(
            "GET",
            "/SyncPlay/List",
            500,
            serde_json::json!({"Body": "secret-name"}),
        );
        match rig.core.sync_play_list_rooms(rig.epoch) {
            Err(CoreError::Api { detail }) => {
                assert_eq!(detail, "watch together list failed: http 500");
            }
            other => panic!("a failed list is fixed text, got {other:?}"),
        }
    }

    /// docs/02 §3: a list answered after its account was replaced is AccountChanged.
    #[test]
    fn rooms_for_a_moved_epoch_is_account_changed() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();
        mock_a.route("GET", "/SyncPlay/List", 200, list_json());
        let held = mock_a.gate("GET", "/SyncPlay/List", 0);
        let epoch = core.account_epoch();
        let listing = {
            let core = core.clone();
            std::thread::spawn(move || core.sync_play_list_rooms(epoch))
        };
        wait_until(
            || mock_a.hit_count("GET", "/SyncPlay/List") == 1,
            "the list is in flight",
        );
        core.switch_session(0).expect("account B is saved");
        held.open();
        let rooms = listing.join().expect("the list returns");
        assert!(matches!(rooms, Err(CoreError::AccountChanged)), "{rooms:?}");
        assert_eq!(mock_b.hit_count("GET", "/SyncPlay/List"), 0);
    }

    /// docs/28 §1: a room is joined by its id alone; nothing is listed.
    #[test]
    fn join_room_posts_that_id_with_no_list() {
        let rig = Rig::up();
        let joined = rig
            .core
            .sync_play_join_room(rig.epoch, GROUP_B.into(), Arc::new(Recorder::default()))
            .expect("installed");
        assert!(matches!(
            joined,
            SyncPlaySnapshot {
                group: SyncPlayGroup::Starting { name: None }
            }
        ));
        wait_until(
            || rig.hits("POST", "/SyncPlay/Join") == 1,
            "the Join goes out",
        );
        let bodies = rig.mock.request_bodies_containing("POST", "/SyncPlay/Join");
        assert_eq!(bodies, vec![serde_json::json!({"GroupId": GROUP_B})]);
        assert_eq!(rig.hits("GET", "/SyncPlay/List"), 0);
    }

    /// docs/28 §4: a malformed room id is refused as fixed text before any step.
    #[test]
    fn join_room_malformed_id_steps_nothing() {
        let rig = Rig::up();
        let recorder = Arc::new(Recorder::default());
        match rig
            .core
            .sync_play_join_room(rig.epoch, "room-secret".into(), recorder.clone())
        {
            Err(CoreError::Api { detail }) => {
                assert_eq!(detail, "watch together: malformed id");
            }
            other => panic!("a malformed id is fixed text, got {other:?}"),
        }
        assert_eq!(Arc::strong_count(&recorder), 1, "no listener kept");
        assert_eq!(rig.snapshot(), SyncPlayGroup::Idle);
        assert_eq!(rig.exec(), "None");
        assert_eq!(rig.mock.hit_count_containing("POST", "/SyncPlay/"), 0);
        assert_eq!(rig.mock.hit_count_containing("GET", "/SyncPlay"), 0);
    }

    /// docs/28 §4: a GroupJoined while Idle is answered with one Leave and registers nothing.
    #[test]
    fn a_ghost_group_joined_while_idle_sends_one_leave_and_registers_nothing() {
        let rig = Rig::up();
        rig.feed(group_joined(GROUP_A, Some("Room A"), &["member-a"]));
        assert_eq!(rig.exec(), "None");
        wait_until(
            || rig.hits("POST", "/SyncPlay/Leave") == 1,
            "the ghost Leave goes out",
        );
        std::thread::sleep(Duration::from_millis(50));
        assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 1);
        assert_eq!(rig.snapshot(), SyncPlayGroup::Idle);
        assert_eq!(rig.hits("GET", "/GetUtcTime"), 0);
    }

    /// docs/28 §4: a create or join whose GroupJoined never comes gives up after the entry timeout
    /// and leaves once, in case the server made us a member anyway.
    #[test]
    fn an_entry_timeout_sends_a_leave() {
        for entry in ["create", "join"] {
            let rig = Rig::up();
            let recorder = Arc::new(Recorder::default());
            if entry == "create" {
                rig.core
                    .sync_play_create(rig.epoch, ROOM_NAME.into(), recorder.clone())
                    .expect("installed");
            } else {
                rig.core
                    .sync_play_join_room(rig.epoch, GROUP_A.into(), recorder.clone())
                    .expect("installed");
            }
            let path = if entry == "create" {
                "/SyncPlay/New"
            } else {
                "/SyncPlay/Join"
            };
            wait_until(|| rig.hits("POST", path) == 1, "the entry request goes out");
            wait_until(
                || rig.hits("POST", "/SyncPlay/Leave") == 1,
                "the timeout's Leave goes out",
            );
            wait_until(
                || rig.snapshot() == SyncPlayGroup::Idle,
                "the Leave's answer returns to Idle",
            );
            assert!(
                recorder.notices().contains(&SyncPlayNotice::StartFailed),
                "{entry}"
            );
            settle();
            assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 1, "{entry}: one Leave");
        }
    }

    /// docs/28 §4: the real forwarder steps the bus's drop: a lobby start fails, a member's link
    /// goes down and it stays in the group.
    #[test]
    fn the_forwarder_steps_a_bus_drop_into_the_group() {
        for member in [false, true] {
            let rig = Rig::up();
            let bus = install_relaying_bus(&rig.core, BusConnection::Connected);
            let recorder = Arc::new(Recorder::default());
            if member {
                rig.member(recorder.clone());
            } else {
                rig.core
                    .sync_play_create(rig.epoch, ROOM_NAME.into(), recorder.clone())
                    .expect("installed");
            }
            bus.connection.send_replace(BusConnection::Disconnected);
            if member {
                wait_until(
                    || {
                        matches!(
                            rig.snapshot(),
                            SyncPlayGroup::Member {
                                connected: false,
                                ..
                            }
                        )
                    },
                    "the member's link goes down",
                );
                assert_eq!(rig.exec(), "Live");
            } else {
                wait_until(|| rig.snapshot() == SyncPlayGroup::Idle, "the start fails");
                assert!(recorder.notices().contains(&SyncPlayNotice::StartFailed));
                assert_eq!(rig.exec(), "None");
            }
            assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 0, "member {member}");
        }
    }

    /// docs/28 §2, §4: the real forwarder steps a socket rejection of the installed bus: the group
    /// ends without a Leave, and only a named token asks to reauthorize; a replaced bus's rejection
    /// changes nothing.
    #[test]
    fn the_forwarder_steps_a_socket_rejection_into_the_group() {
        let owner = jellyfin_api::TokenOwner {
            server_url: "http://server-a.test".to_string(),
            user_id: "00000000-0000-4000-8000-000000000001".to_string(),
        };
        for (row, named, replaced, asks) in [
            ("unnamed", false, false, 0),
            ("named", true, false, 1),
            ("replaced bus", true, true, 0),
        ] {
            let rig = Rig::up();
            let bus = install_relaying_bus(&rig.core, BusConnection::Connected);
            let changes = Arc::new(ChangeCounter::default());
            rig.core.set_change_listener(changes.clone());
            let recorder = Arc::new(Recorder::default());
            rig.member(recorder.clone());
            let forwarder = replaced
                .then(|| take_bundle_leaving_forwarder(&rig.core).expect("the relay's forwarder"));
            let owner = named.then(|| owner.clone());
            bus.connection
                .send_replace(BusConnection::Unauthorized { owner });
            let ended = SyncPlayNotice::GroupEnded {
                cause: SyncPlayEndCause::Auth,
            };
            if replaced {
                settle();
                assert!(
                    matches!(rig.snapshot(), SyncPlayGroup::Member { .. }),
                    "{row}"
                );
                assert!(!recorder.notices().contains(&ended), "{row}");
            } else {
                wait_until(|| rig.snapshot() == SyncPlayGroup::Idle, "the group ends");
                wait_until(
                    || recorder.notices().contains(&ended),
                    "the end is delivered",
                );
                settle();
            }
            assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 0, "{row}");
            assert_eq!(changes.unauthorized(), asks, "{row}");
            if let Some(forwarder) = forwarder {
                forwarder.abort();
            }
        }
    }

    /// docs/28 §2, §4: a rejected socket token ends the group without a Leave, which would 401 too.
    #[test]
    fn bus_unauthorized_ends_the_group_without_a_leave() {
        let rig = Rig::up();
        let recorder = Arc::new(Recorder::default());
        rig.playing(recorder.clone());
        rig.feed(Event::BusUnauthorized);
        assert_eq!(rig.snapshot(), SyncPlayGroup::Idle);
        assert_eq!(rig.exec(), "None", "the scope is gone");
        wait_until(
            || {
                recorder.notices().contains(&SyncPlayNotice::GroupEnded {
                    cause: SyncPlayEndCause::Auth,
                })
            },
            "the end reaches the listener",
        );
        assert!(recorder
            .directives()
            .iter()
            .any(|d| d.directive == SyncPlayDirective::Detach));
        let samples = rig.hits("GET", "/GetUtcTime");
        std::thread::sleep(scaled(5_000));
        assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 0);
        assert_eq!(rig.hits("GET", "/GetUtcTime"), samples, "time sync stopped");
        assert_eq!(rig.core.account_epoch(), rig.epoch);
    }

    /// The installed account's group, taken out of the state as an account reset takes it, with
    /// what its steps run with.
    fn take_group(rig: &Rig) -> (SyncPlayOwner, StepCtx) {
        let mut state = rig.core.lock_state();
        let installed = state.account.installed_mut(rig.epoch).expect("installed");
        let ctx = rig.core.sync_play_ctx(rig.epoch, &installed.client);
        (std::mem::take(&mut installed.syncplay), ctx)
    }

    /// docs/28 §4: an account reset leaves once from Creating, Joining or Member and never from
    /// Leaving or Idle; it hands over the settle of the Leave it sent or found in flight.
    #[test]
    fn an_account_reset_leaves_once_from_a_started_group_and_never_otherwise() {
        for (from, leaves, settles) in [
            ("Idle", 0, false),
            ("Creating", 1, true),
            ("Joining", 1, true),
            ("Member", 1, true),
            ("Leaving", 0, true),
        ] {
            let rig = Rig::up();
            let mut held = None;
            match from {
                "Creating" => {
                    rig.core
                        .sync_play_create(rig.epoch, ROOM_NAME.into(), listener())
                        .expect("installed");
                    rig.wait_entry_answered();
                }
                "Joining" => {
                    rig.core
                        .sync_play_join_room(rig.epoch, GROUP_A.into(), listener())
                        .expect("installed");
                    rig.wait_entry_answered();
                }
                "Member" => rig.member(Arc::new(Recorder::default())),
                "Leaving" => {
                    rig.member(Arc::new(Recorder::default()));
                    held = Some(rig.mock.gate("POST", "/SyncPlay/Leave", 0));
                    rig.input(SyncPlayInput::Leave);
                    wait_until(
                        || rig.hits("POST", "/SyncPlay/Leave") == 1,
                        "our Leave is out",
                    );
                }
                _ => {}
            }
            let before = rig.hits("POST", "/SyncPlay/Leave");
            let (group, ctx) = take_group(&rig);
            let left = group.take_for_reset(&ctx);
            assert_eq!(left.is_some(), settles, "{from}");
            if let Some(held) = held {
                held.open();
            }
            if let Some(left) = left {
                rig.core
                    .runtime()
                    .block_on(async { tokio::time::timeout(Duration::from_secs(2), left).await })
                    .expect("the settle fires")
                    .expect("the Leave task sends it");
            }
            settle();
            assert_eq!(
                rig.hits("POST", "/SyncPlay/Leave") - before,
                leaves,
                "{from}"
            );
        }
    }

    /// docs/28 §4: a core torn down on a runtime thread sends its Leave without waiting, never
    /// panicking on a blocking wait.
    #[test]
    fn a_shutdown_leave_on_a_runtime_thread_is_sent_without_waiting() {
        let rig = Rig::up();
        rig.member(Arc::new(Recorder::default()));
        let (group, ctx) = take_group(&rig);
        rig.core
            .runtime()
            .block_on(async { group.leave_on_shutdown(&ctx) });
        wait_until(
            || rig.hits("POST", "/SyncPlay/Leave") == 1,
            "the Leave still goes out",
        );
    }

    /// docs/28 §4: a dropping core waits at most its budget for an unanswered Leave.
    #[test]
    fn dropping_the_core_waits_at_most_its_budget_for_the_leave() {
        let rig = Rig::up();
        rig.member(Arc::new(Recorder::default()));
        let _held = rig.mock.gate("POST", "/SyncPlay/Leave", 0);
        let Rig {
            core, mock, bus, ..
        } = rig;
        drop(bus);
        let started = Instant::now();
        drop(core);
        assert!(
            started.elapsed() < SHUTDOWN_LEAVE_BUDGET + Duration::from_secs(1),
            "took {:?}",
            started.elapsed()
        );
        assert_eq!(mock.hit_count("POST", "/SyncPlay/Leave"), 1);
    }

    /// docs/28 §4: a sign-out whose group Leave is never answered logs out once the settle budget
    /// ends.
    #[test]
    fn sign_out_with_an_unanswered_leave_logs_out_after_the_budget() {
        let rig = Rig::up();
        rig.mock
            .route("POST", "/Sessions/Logout", 200, serde_json::json!({}));
        rig.member(Arc::new(Recorder::default()));
        let held = rig.mock.gate("POST", "/SyncPlay/Leave", 0);
        let started = Instant::now();
        assert!(rig.core.remove_session(0).expect("the account is saved"));
        wait_until(
            || rig.hits("POST", "/SyncPlay/Leave") == 1,
            "the group leaves",
        );
        settle();
        assert_eq!(rig.hits("POST", "/Sessions/Logout"), 0, "the logout waits");
        wait_until_for(
            FINAL_REPORT_SETTLE_BUDGET + Duration::from_secs(2),
            || rig.hits("POST", "/Sessions/Logout") == 1,
            "the logout goes once the budget ends",
        );
        assert!(started.elapsed() >= FINAL_REPORT_SETTLE_BUDGET);
        held.open();
    }

    /// The installed account's group as the forwarder would step it.
    fn feed_installed(core: &JellybeamCore, event: Event) -> bool {
        let _rt = core.runtime().enter();
        sync_play_feed(&weak_state(core), core.account_epoch(), event)
    }

    /// Puts a connected test bus on the installed account and joins `GROUP_A`.
    fn join_on(core: &JellybeamCore, listener: Arc<Recorder>) {
        connect_bus(core);
        core.sync_play_join_room(core.account_epoch(), GROUP_A.into(), listener)
            .expect("installed");
    }

    /// docs/28 §4: an account switch leaves the outgoing account's group once, with that account's
    /// own token, even while another account's playback is parked.
    #[test]
    fn an_account_switch_mid_group_leaves_once_with_the_outgoing_token() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();
        admit(&core);
        core.switch_session(0).expect("account B is saved");
        let recorder = Arc::new(Recorder::default());
        join_on(&core, recorder.clone());
        wait_entry_answered(&core);
        assert!(feed_installed(
            &core,
            group_joined(GROUP_A, None, &["member-b"])
        ));
        core.switch_session(1).expect("account A is saved");
        wait_until(
            || mock_b.hit_count("POST", "/SyncPlay/Leave") == 1,
            "the outgoing group leaves",
        );
        std::thread::sleep(Duration::from_millis(100));
        assert_eq!(mock_b.hit_count("POST", "/SyncPlay/Leave"), 1, "one Leave");
        assert_eq!(mock_a.hit_count("POST", "/SyncPlay/Leave"), 0);
        let auth = mock_b.authorizations("POST", "/SyncPlay/Leave");
        assert!(auth[0].contains(r#"Token="tok-b""#), "{auth:?}");
        wait_until(
            || {
                recorder
                    .directives()
                    .iter()
                    .any(|d| d.directive == SyncPlayDirective::Detach)
            },
            "the outgoing player goes solo",
        );
        let epoch = core.account_epoch();
        assert_eq!(
            core.sync_play_snapshot(epoch).expect("installed").group,
            SyncPlayGroup::Idle,
            "the incoming account starts Idle"
        );
    }

    /// docs/28 §4: signing out mid-group sends the Leave first; the logout waits for its answer.
    #[test]
    fn sign_out_mid_group_leaves_before_the_logout() {
        let rig = Rig::up();
        rig.mock
            .route("POST", "/Sessions/Logout", 200, serde_json::json!({}));
        rig.member(Arc::new(Recorder::default()));
        let held = rig.mock.gate("POST", "/SyncPlay/Leave", 0);
        assert!(rig.core.remove_session(0).expect("the account is saved"));
        wait_until(
            || rig.hits("POST", "/SyncPlay/Leave") == 1,
            "the group leaves",
        );
        std::thread::sleep(Duration::from_millis(100));
        assert_eq!(
            rig.hits("POST", "/Sessions/Logout"),
            0,
            "the logout waits for the Leave's answer"
        );
        held.open();
        wait_until(
            || rig.hits("POST", "/Sessions/Logout") == 1,
            "the logout follows",
        );
    }

    /// docs/02 §3: a Join answered after its account was replaced steps nothing into the new
    /// account's group, although both groups' first requests carry the same tag.
    #[test]
    fn a_result_from_the_previous_account_changes_nothing() {
        let (_dir, core, mock_a, mock_b) = two_accounts_on_mocks();
        mock_a.route("POST", "/SyncPlay/Join", 403, serde_json::json!({}));
        let held = mock_a.gate("POST", "/SyncPlay/Join", 0);
        join_on(&core, Arc::new(Recorder::default()));
        wait_until(
            || mock_a.hit_count("POST", "/SyncPlay/Join") == 1,
            "the Join is in flight",
        );
        core.switch_session(0).expect("account B is saved");
        connect_bus(&core);
        mock_b.route("GET", "/SyncPlay/List", 200, serde_json::json!([]));
        let epoch = core.account_epoch();
        let recorder = Arc::new(Recorder::default());
        core.sync_play_create(epoch, ROOM_NAME.into(), recorder.clone())
            .expect("installed");
        held.open();
        std::thread::sleep(Duration::from_millis(30));
        assert!(
            matches!(
                core.sync_play_snapshot(epoch).expect("installed").group,
                SyncPlayGroup::Starting { name: Some(_) }
            ),
            "the new group is still starting"
        );
        assert!(!recorder.notices().contains(&SyncPlayNotice::StartFailed));
        assert_eq!(mock_b.hit_count("POST", "/SyncPlay/Join"), 0);
    }

    /// docs/28 §4: an account switch while the outgoing account's Join is unanswered leaves at once
    /// and once more after that Join answers, both with the outgoing token.
    #[test]
    fn an_account_switch_mid_join_leaves_at_once_and_again_after_the_join_answers() {
        let (_dir, core, mock_a, _mock_b) = two_accounts_on_mocks();
        let held = mock_a.gate("POST", "/SyncPlay/Join", 0);
        join_on(&core, Arc::new(Recorder::default()));
        wait_until(
            || mock_a.hit_count("POST", "/SyncPlay/Join") == 1,
            "the Join is in flight",
        );
        core.switch_session(0).expect("account B is saved");
        wait_until(
            || mock_a.hit_count("POST", "/SyncPlay/Leave") == 1,
            "the Leave goes out at once",
        );
        // Past the final-report budget: a late answer still gets its Leave.
        std::thread::sleep(FINAL_REPORT_SETTLE_BUDGET + Duration::from_millis(200));
        assert_eq!(
            mock_a.hit_count("POST", "/SyncPlay/Leave"),
            1,
            "the second Leave waits for the Join's answer"
        );
        held.open();
        wait_until(
            || mock_a.hit_count("POST", "/SyncPlay/Leave") == 2,
            "a second Leave follows the Join's answer",
        );
        settle();
        assert_eq!(mock_a.hit_count("POST", "/SyncPlay/Leave"), 2, "two Leaves");
        let auth = mock_a.authorizations("POST", "/SyncPlay/Leave");
        assert!(
            auth.iter().all(|a| a.contains(r#"Token="tok-a""#)),
            "{auth:?}"
        );
    }

    /// docs/28 §4: a sign-out while the Join is unanswered leaves at once, leaves again after the
    /// Join answers, and only then logs out.
    #[test]
    fn sign_out_mid_join_logs_out_after_the_second_leave() {
        let rig = Rig::up();
        rig.mock
            .route("POST", "/Sessions/Logout", 200, serde_json::json!({}));
        let held = rig.mock.gate("POST", "/SyncPlay/Join", 0);
        rig.core
            .sync_play_join_room(rig.epoch, GROUP_A.into(), listener())
            .expect("installed");
        wait_until(
            || rig.hits("POST", "/SyncPlay/Join") == 1,
            "the Join is in flight",
        );
        assert!(rig.core.remove_session(0).expect("the account is saved"));
        wait_until(
            || rig.hits("POST", "/SyncPlay/Leave") == 1,
            "the Leave goes out at once",
        );
        settle();
        assert_eq!(rig.hits("POST", "/Sessions/Logout"), 0, "the logout waits");
        held.open();
        wait_until(
            || rig.hits("POST", "/Sessions/Logout") == 1,
            "the logout follows",
        );
        assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 2);
        let log = rig.mock.log_snapshot();
        let last_leave = log
            .iter()
            .rposition(|e| e.starts_with("POST /SyncPlay/Leave"))
            .expect("the Leaves arrived");
        let logout = log
            .iter()
            .position(|e| e.starts_with("POST /Sessions/Logout"))
            .expect("the logout arrived");
        assert!(last_leave < logout, "{log:?}");
    }

    /// docs/28 §4: a user's Leave while the Join is unanswered is sent again after that Join
    /// answers, with the outgoing token, although an account switch came in between.
    #[test]
    fn a_leave_mid_join_then_an_account_switch_leaves_again_after_the_join_answers() {
        let (_dir, core, mock_a, _mock_b) = two_accounts_on_mocks();
        let held = mock_a.gate("POST", "/SyncPlay/Join", 0);
        join_on(&core, Arc::new(Recorder::default()));
        wait_until(
            || mock_a.hit_count("POST", "/SyncPlay/Join") == 1,
            "the Join is in flight",
        );
        core.sync_play_input(core.account_epoch(), SyncPlayInput::Leave)
            .expect("installed");
        wait_until(
            || mock_a.hit_count("POST", "/SyncPlay/Leave") == 1,
            "the Leave goes out at once",
        );
        core.switch_session(0).expect("account B is saved");
        settle();
        assert_eq!(mock_a.hit_count("POST", "/SyncPlay/Leave"), 1);
        held.open();
        wait_until(
            || mock_a.hit_count("POST", "/SyncPlay/Leave") == 2,
            "a second Leave follows the Join's answer",
        );
        settle();
        assert_eq!(mock_a.hit_count("POST", "/SyncPlay/Leave"), 2, "two Leaves");
        let auth = mock_a.authorizations("POST", "/SyncPlay/Leave");
        assert!(
            auth.iter().all(|a| a.contains(r#"Token="tok-a""#)),
            "{auth:?}"
        );
    }

    /// docs/28 §4: a core dropped while its New is unanswered sends its Leave within the shutdown
    /// budget.
    #[test]
    fn dropping_the_core_mid_create_sends_its_leave_within_the_budget() {
        let rig = Rig::up();
        let _held = rig.mock.gate("POST", "/SyncPlay/New", 0);
        rig.core
            .sync_play_create(rig.epoch, ROOM_NAME.into(), listener())
            .expect("installed");
        wait_until(
            || rig.hits("POST", "/SyncPlay/New") == 1,
            "the New is in flight",
        );
        let Rig {
            core, mock, bus, ..
        } = rig;
        drop(bus);
        let started = Instant::now();
        drop(core);
        assert!(
            started.elapsed() < SHUTDOWN_LEAVE_BUDGET + Duration::from_secs(1),
            "took {:?}",
            started.elapsed()
        );
        assert_eq!(mock.hit_count("POST", "/SyncPlay/Leave"), 1);
    }

    /// docs/28 §4: a ghost Leave for another group, sent while our New is unanswered, is not an
    /// exit, so no second Leave follows our New's answer.
    #[test]
    fn a_ghost_leave_while_creating_sends_no_second_leave() {
        let rig = Rig::up();
        let held = rig.mock.gate("POST", "/SyncPlay/New", 0);
        rig.core
            .sync_play_create(rig.epoch, ROOM_NAME.into(), listener())
            .expect("installed");
        wait_until(
            || rig.hits("POST", "/SyncPlay/New") == 1,
            "the New is in flight",
        );
        rig.feed(group_joined(GROUP_B, Some("OTHER9"), &["member-b"]));
        wait_until(
            || rig.hits("POST", "/SyncPlay/Leave") == 1,
            "the ghost Leave goes out",
        );
        held.open();
        settle();
        assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 1, "no second Leave");
        assert!(matches!(rig.snapshot(), SyncPlayGroup::Starting { .. }));
    }

    /// docs/28 §4: a sign-out while our own Leave is unanswered still holds the logout for that
    /// Leave, so the token outlives it.
    #[test]
    fn sign_out_while_our_leave_is_in_flight_waits_for_its_answer() {
        let rig = Rig::up();
        rig.mock
            .route("POST", "/Sessions/Logout", 200, serde_json::json!({}));
        rig.member(Arc::new(Recorder::default()));
        let held = rig.mock.gate("POST", "/SyncPlay/Leave", 0);
        rig.input(SyncPlayInput::Leave);
        assert!(matches!(rig.snapshot(), SyncPlayGroup::Leaving { .. }));
        assert_eq!(
            rig.exec(),
            "Draining",
            "the Leave in flight drains the group"
        );
        wait_until(
            || rig.hits("POST", "/SyncPlay/Leave") == 1,
            "the Leave is in flight",
        );
        assert!(rig.core.remove_session(0).expect("the account is saved"));
        settle();
        assert_eq!(rig.hits("POST", "/Sessions/Logout"), 0, "the logout waits");
        held.open();
        wait_until(
            || rig.hits("POST", "/Sessions/Logout") == 1,
            "the logout follows the Leave's answer",
        );
        assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 1, "no second Leave");
    }

    /// The account's serial request runner on its own, fed by the test.
    fn runner(rig: &Rig) -> (JoinSet<()>, mpsc::UnboundedSender<Request>) {
        let ctx = {
            let state = rig.core.lock_state();
            let installed = state.account.installed().expect("installed");
            rig.core.sync_play_ctx(rig.epoch, &installed.client)
        };
        let mut set = JoinSet::new();
        let requests = spawn_runner(&mut set, &ctx);
        (set, requests)
    }

    fn report(position_ticks: i64) -> sp::PlayerReport {
        sp::PlayerReport {
            when_ms: 0,
            position_ticks,
            is_playing: false,
            pid: uuid(PID),
        }
    }

    /// docs/28 §5: a Ready or Buffering lost to a 5xx is retried up to three times a second apart,
    /// ahead of later requests; a 4xx is final.
    #[test]
    fn a_failed_report_is_retried_on_5xx_only_and_keeps_later_requests_in_order() {
        for path in ["/SyncPlay/Ready", "/SyncPlay/Buffering"] {
            let kind = match path {
                "/SyncPlay/Ready" => RestCall::Ready,
                _ => RestCall::Buffering,
            };
            for (fail, status, want) in [(2, 500, 3), (6, 500, 4), (1, 400, 1)] {
                let rig = Rig::up();
                if status == 500 {
                    rig.mock.fail_first("POST", path, fail);
                } else {
                    rig.mock.route("POST", path, status, serde_json::json!({}));
                }
                let (_set, requests) = runner(&rig);
                for (req, call) in [(1, kind(report(0))), (2, RestCall::Unpause)] {
                    requests
                        .send((Tag(req), call, None))
                        .expect("the runner listens");
                }
                wait_until(
                    || rig.hits("POST", "/SyncPlay/Unpause") == 1,
                    "the Unpause follows",
                );
                settle();
                let row = format!("{path}: {fail} x {status}");
                assert_eq!(rig.hits("POST", path), want, "{row}");
                let log = rig.mock.log_snapshot();
                let last = log
                    .iter()
                    .rposition(|e| e.starts_with(&format!("POST {path}")))
                    .expect("a report went out");
                let unpause = log
                    .iter()
                    .position(|e| e.starts_with("POST /SyncPlay/Unpause"))
                    .expect("the Unpause went out");
                assert!(last < unpause, "{row}: {log:?}");
            }
        }
    }

    /// docs/28 §5: a report is resent after a connection failure or a 5xx, never after a request
    /// timeout (a hung server would hold the queue for minutes) or any other answer.
    #[test]
    fn only_a_connection_failure_or_a_5xx_is_worth_resending() {
        let status = |code| ApiError::Status {
            code,
            body: String::new(),
        };
        let rows = [
            (
                ApiError::Transport("tcp connect error: Connection refused".into()),
                true,
            ),
            (ApiError::Transport("connection reset by peer".into()), true),
            (
                ApiError::Transport("error sending request: operation timed out".into()),
                false,
            ),
            (status(500), true),
            (status(503), true),
            (status(400), false),
            (status(403), false),
            (status(404), false),
            (ApiError::Decode("expected value".into()), false),
            (ApiError::Unauthorized { owner: None }, false),
        ];
        for (row, (error, want)) in rows.into_iter().enumerate() {
            assert_eq!(transient(&error), want, "row {row}");
        }
    }

    /// docs/28 §5: a report waiting to be resent gives way to a newer one of its kind queued behind
    /// it, which is then resent as often as any.
    #[test]
    fn a_newer_ready_supersedes_one_waiting_to_be_resent() {
        let rig = Rig::up();
        rig.mock
            .route("POST", "/SyncPlay/Ready", 500, serde_json::json!({}));
        let (_set, requests) = runner(&rig);
        for (req, position) in [(1, 1), (2, 2)] {
            requests
                .send((Tag(req), RestCall::Ready(report(position)), None))
                .expect("the runner listens");
        }
        wait_until(
            || rig.hits("POST", "/SyncPlay/Ready") == 5,
            "the newer Ready is resent",
        );
        settle();
        let positions: Vec<serde_json::Value> = rig
            .mock
            .request_bodies_containing("POST", "/SyncPlay/Ready")
            .into_iter()
            .map(|body| body["PositionTicks"].clone())
            .collect();
        assert_eq!(positions, [1, 2, 2, 2, 2].map(serde_json::Value::from));
    }

    /// docs/28 §4: a pick or queue with no items, or a pick index outside the list, is refused
    /// as fixed text before any step.
    #[test]
    fn an_empty_queue_or_an_index_outside_it_is_refused() {
        let rig = Rig::up();
        rig.playing(Arc::new(Recorder::default()));
        let inputs = [
            SyncPlayInput::PickItem {
                item_ids: vec![],
                index: 0,
                start_ticks: 0,
            },
            SyncPlayInput::PickItem {
                item_ids: vec![ITEM.into()],
                index: 1,
                start_ticks: 0,
            },
            SyncPlayInput::PickItem {
                item_ids: vec![ITEM.into()],
                index: -1,
                start_ticks: 0,
            },
            SyncPlayInput::Queue {
                item_ids: vec![],
                mode: SyncPlayQueueMode::Queue,
            },
        ];
        for input in inputs {
            let name = format!("{input:?}");
            match rig.core.sync_play_input(rig.epoch, input) {
                Err(CoreError::Api { detail }) => {
                    assert_eq!(
                        detail, "watch together: empty queue or index outside it",
                        "{name}"
                    );
                }
                other => panic!("{name} is refused, got {other:?}"),
            }
        }
        settle();
        assert_eq!(rig.hits("POST", "/SyncPlay/SetNewQueue"), 0);
        assert_eq!(rig.hits("POST", "/SyncPlay/Queue"), 0);
    }

    /// docs/28 §4: a connect whose server refresh returns after its bus was replaced starts no
    /// snapshot window, so it never rejoins on a bus the group no longer follows; on the bus still
    /// installed the same connect rejoins after the window.
    #[test]
    fn a_connect_refreshed_after_its_bus_was_replaced_starts_no_window() {
        for replaced in [false, true] {
            let rig = Rig::up();
            let bus = install_relaying_bus(&rig.core, BusConnection::Connected);
            rig.member(Arc::new(Recorder::default()));
            bus.connection.send_replace(BusConnection::Disconnected);
            wait_until(
                || {
                    matches!(
                        rig.snapshot(),
                        SyncPlayGroup::Member {
                            connected: false,
                            ..
                        }
                    )
                },
                "the drop reaches the group",
            );
            let refreshes = rig.hits("GET", "/System/Info/Public");
            let held = rig.mock.gate("GET", "/System/Info/Public", refreshes);
            bus.connection.send_replace(BusConnection::Connected);
            wait_until(
                || rig.hits("GET", "/System/Info/Public") > refreshes,
                "the connect refreshes the server first",
            );
            // Taken as a teardown takes it, before the teardown stops its forwarder.
            let forwarder = replaced
                .then(|| take_bundle_leaving_forwarder(&rig.core).expect("the relay's forwarder"));
            held.open();
            if replaced {
                std::thread::sleep(scaled(4_000));
                assert_eq!(
                    rig.hits("POST", "/SyncPlay/Join"),
                    0,
                    "no window on a replaced bus"
                );
            } else {
                wait_until(
                    || rig.hits("POST", "/SyncPlay/Join") == 1,
                    "the window on the installed bus ends in a rejoin",
                );
            }
            if let Some(forwarder) = forwarder {
                forwarder.abort();
            }
        }
    }

    /// docs/28 §4: dropping the core mid-group sends one best-effort Leave; an Idle core sends none.
    #[test]
    fn dropping_the_core_mid_group_sends_one_leave() {
        for member in [true, false] {
            let rig = Rig::up();
            if member {
                rig.member(Arc::new(Recorder::default()));
            }
            let Rig {
                core, mock, bus, ..
            } = rig;
            drop(bus);
            drop(core);
            assert_eq!(
                mock.hit_count("POST", "/SyncPlay/Leave"),
                usize::from(member),
                "member {member}"
            );
        }
    }

    /// docs/28 §4: restoring the account already installed is no account change; the group stays.
    #[test]
    fn restore_of_the_installed_account_keeps_the_group() {
        let rig = Rig::up();
        rig.member(Arc::new(Recorder::default()));
        assert!(rig.core.restore_session().is_some());
        assert_eq!(rig.core.account_epoch(), rig.epoch);
        assert!(matches!(rig.snapshot(), SyncPlayGroup::Member { .. }));
        assert_eq!(rig.exec(), "Live");
        std::thread::sleep(Duration::from_millis(50));
        assert_eq!(rig.hits("POST", "/SyncPlay/Leave"), 0);
    }

    /// docs/28 §1: a join finds our own entry by the installed account's user name, so the one
    /// member listed besides us hosts in whatever order the server lists, and several are unnamed.
    #[test]
    fn a_join_seats_the_host_by_the_installed_account_name() {
        let me = "jellybeam-user";
        for (listed, want) in [
            (
                [me, "member-a"].as_slice(),
                SyncPlayHost::Known {
                    name: "member-a".into(),
                },
            ),
            (&["member-a", "member-c", me], SyncPlayHost::Among),
        ] {
            let rig = Rig::up();
            rig.core
                .sync_play_join_room(rig.epoch, GROUP_A.into(), Arc::new(Recorder::default()))
                .expect("installed");
            rig.wait_entry_answered();
            assert!(rig.feed(group_joined(GROUP_A, Some("Room A"), listed)));
            let SyncPlayGroup::Member {
                role,
                stage,
                host,
                participants,
                ..
            } = rig.snapshot()
            else {
                panic!("a member: {:?}", rig.snapshot());
            };
            assert_eq!(role, SyncPlayRole::Guest);
            assert_eq!(stage, SyncPlayStage::Lobby);
            assert_eq!(host, want, "{listed:?}");
            assert_eq!(participants, listed, "server order, verbatim");
        }
    }

    /// docs/28 §3.6: no FFI value carrying a user name, room name or id shows it in Debug.
    #[test]
    fn debug_of_every_syncplay_ffi_value_with_a_user_or_room_name_or_id_hides_it() {
        let (name, room) = ("member-secret".to_string(), "room-secret".to_string());
        let member = SyncPlayGroup::Member {
            role: SyncPlayRole::Guest,
            stage: SyncPlayStage::Finished,
            host: SyncPlayHost::Known { name: name.clone() },
            participants: vec![name.clone()],
            phase: SyncPlayPhase::Playing,
            connected: true,
            prompt_shown: false,
        };
        let starting = SyncPlaySnapshot {
            group: SyncPlayGroup::Starting {
                name: Some(room.clone()),
            },
        };
        let update = SyncPlayUpdate {
            directives: vec![SyncPlayDirectiveAt {
                seq: 1,
                directive: SyncPlayDirective::Load {
                    item_id: ITEM.into(),
                    playlist_item_id: PID.into(),
                    start_ticks: 0,
                },
            }],
            notices: vec![
                SyncPlayNotice::UserJoined { name: name.clone() },
                SyncPlayNotice::UserLeft { name: name.clone() },
            ],
            changed: true,
        };
        let rendered = [
            format!("{member:?}"),
            format!("{:?}", SyncPlayHost::Known { name: name.clone() }),
            format!("{starting:?}"),
            format!("{update:?}"),
            format!(
                "{:?}",
                SyncPlayInput::PickItem {
                    item_ids: vec![ITEM.into()],
                    index: 0,
                    start_ticks: 0
                }
            ),
            format!(
                "{:?}",
                SyncPlayInput::ItemLoaded {
                    playlist_item_id: PID.into(),
                    landed_ticks: 0
                }
            ),
            format!(
                "{:?}",
                SyncPlayInput::Queue {
                    item_ids: vec![ITEM.into()],
                    mode: SyncPlayQueueMode::QueueNext
                }
            ),
            format!(
                "{:?}",
                SyncPlayRooms::Listed {
                    rooms: vec![SyncPlayRoom {
                        group_id: GROUP_A.into(),
                        name: room.clone(),
                        participants: vec![name.clone()],
                        phase: Some(SyncPlayPhase::Waiting),
                    }]
                }
            ),
        ];
        for text in rendered {
            for secret in [name.as_str(), room.as_str(), ITEM, PID, GROUP_A] {
                assert!(!text.contains(secret), "{text} names {secret}");
            }
        }
    }
}
