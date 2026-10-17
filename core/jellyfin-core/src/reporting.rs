//! Playback reporting state machine: start/progress(10s)/stopped scheduling,
//! pause/seek/track-change edge reports, retry-with-backoff. Timing/retry is generic over a private
//! [`ReportSink`] trait so tests can drive it deterministically with `tokio::time::pause` +
//! `advance`.
//!
//! The final `Stopped` report is processed by the session's actor task, attempted inline with
//! failures handed to a shared bounded [`FlushQueue`]. If the actor hasn't begun that delivery
//! within [`STOP_ACK_TIMEOUT`], `stop()` aborts it and enqueues the report itself; exactly one of
//! them owns the Stopped report, so the server never sees it twice. None of this survives process
//! exit.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use jellyfin_api::{ApiError, JellyfinClient, PlaybackReport, PlaybackReportKind};
use tokio::sync::{mpsc, oneshot, watch};

use crate::backoff::Backoff;
use crate::ReportContext;

const PROGRESS_INTERVAL: Duration = Duration::from_secs(10);
const RETRY_BASE: Duration = Duration::from_secs(1);
const RETRY_CAP: Duration = Duration::from_secs(60);
/// Start/progress reports are superseded 10s later, so they aren't retried forever -- only the
/// final Stopped report gets that guarantee (see [`FlushQueue`]).
const TRANSIENT_REPORT_MAX_ATTEMPTS: u32 = 5;
/// No volume control is wired in yet; report max volume so the server doesn't record a misleadingly
/// low value.
const DEFAULT_VOLUME: u8 = 100;
/// Ceiling on how long the background flush queue keeps retrying one final Stopped report --
/// comfortably covers a Wi-Fi blip, server restart, or brief partition, without a
/// permanently-unreachable server accumulating a background task forever.
const FLUSH_MAX_DURATION: Duration = Duration::from_secs(30 * 60);
/// Position updates arrive in bursts while the user drags a scrub bar; a detected seek is only
/// reported once updates go quiet for this long, so a burst of jumps collapses into one report.
const SEEK_DEBOUNCE: Duration = Duration::from_millis(500);
/// How long `stop()` waits for the actor to acknowledge final delivery before taking it over,
/// when the actor hasn't begun it yet.
const STOP_ACK_TIMEOUT: Duration = Duration::from_millis(100);

/// Who sends the final Stopped report: claimed once, by the actor when it begins delivery or by
/// `stop()` when the actor hasn't within [`STOP_ACK_TIMEOUT`]. A Stopped already on the wire is
/// never re-sent: Jellyfin can take over a second to process one (it ends the transcode), and a
/// duplicate reaches the server as a second playback stop.
const FINAL_UNCLAIMED: u8 = 0;
const FINAL_BY_ACTOR: u8 = 1;
const FINAL_BY_CALLER: u8 = 2;

fn claim_final(owner: &AtomicU8, by: u8) -> bool {
    owner
        .compare_exchange(FINAL_UNCLAIMED, by, Ordering::AcqRel, Ordering::Acquire)
        .is_ok()
}

type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub(crate) trait ReportSink: Send + Sync + 'static {
    fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>>;
}

impl ReportSink for JellyfinClient {
    fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
        Box::pin(self.report_playback(report))
    }
}

#[derive(Debug, Clone, Copy)]
struct PlaybackState {
    position_ticks: i64,
    is_paused: bool,
    audio_stream_index: Option<i32>,
    subtitle_stream_index: Option<i32>,
}

enum Cmd {
    Pause(bool),
    /// Processed by the reporting actor, not directly by `ReportingSession::stop`, so a slow Start
    /// can never land after Stopped.
    Stop {
        ticks: i64,
        completion: oneshot::Sender<()>,
        delivered: DeliverySlot,
    },
}

/// Held by whichever side owns the final Stopped report; dropping it on any path (landed, retries
/// gave up, superseded, dedup, abort) releases the item's [`PendingHold`] and then settles the
/// matching [`FinalDelivery`] (docs/07 Watch state).
struct Delivery {
    // Declared first so the item is released before the waiter wakes.
    hold: PendingHold,
    _settled: oneshot::Sender<()>,
}

impl Delivery {
    /// Resolves once a newer action cancelled this delivery (docs/07 Watch state, Ordering).
    async fn wait_cancelled(&self) {
        match self.hold.cancel() {
            Some(cancel) => {
                cancel
                    .wait_for(|state| state == CancelState::Cancelled)
                    .await
            }
            None => std::future::pending().await,
        }
    }

    /// Resolves once the delivery is no longer paused by a pending mark.
    async fn wait_unpaused(&self) {
        if let Some(cancel) = self.hold.cancel() {
            cancel.wait_for(|state| state != CancelState::Paused).await;
        }
    }
}

/// Sleeps `delay`; returns false, without finishing it, if the delivery is cancelled meanwhile.
async fn sleep_unless_cancelled(delay: Duration, delivery: Option<&Delivery>) -> bool {
    let Some(delivery) = delivery else {
        tokio::time::sleep(delay).await;
        return true;
    };
    tokio::select! {
        () = tokio::time::sleep(delay) => true,
        () = delivery.wait_cancelled() => false,
    }
}

/// What became of one request a final delivery tried to send.
enum Sent {
    Done(Result<(), ApiError>),
    /// A mark is pending on the item: nothing was sent, and the delivery may resume.
    Paused,
    /// A newer action cancelled the delivery: nothing was sent, and nothing will be.
    Cancelled,
}

/// One request of a final delivery (the Start-repair and the Stopped alike), counted on the wire
/// for [`supersede`] to wait on; nothing is sent while the delivery is paused or cancelled.
async fn send_final_request(
    sink: &Arc<dyn ReportSink>,
    delivery: Option<&Delivery>,
    report: PlaybackReport,
) -> Sent {
    let _attempt = match delivery.map(|delivery| delivery.hold.begin_attempt()) {
        Some(Begin::Go(attempt)) => Some(attempt),
        Some(Begin::Paused) => return Sent::Paused,
        Some(Begin::Cancelled) => return Sent::Cancelled,
        None => None,
    };
    Sent::Done(sink.report(report).await)
}

/// [`send_final_request`] for the flush loop: waits out a pause, so `None` means cancelled.
async fn send_final_request_waiting(
    sink: &Arc<dyn ReportSink>,
    delivery: Option<&Delivery>,
    report: &PlaybackReport,
) -> Option<Result<(), ApiError>> {
    loop {
        match send_final_request(sink, delivery, report.clone()).await {
            Sent::Done(result) => return Some(result),
            Sent::Cancelled => return None,
            Sent::Paused => delivery?.wait_unpaused().await,
        }
    }
}

type DeliverySlot = Arc<Mutex<Option<Delivery>>>;

fn take_delivery(slot: &DeliverySlot) -> Option<Delivery> {
    slot.lock().unwrap_or_else(|e| e.into_inner()).take()
}

/// Settles once the server has the final Stopped report, its bounded retries gave up, or a newer
/// action cancelled it; work that the server must see after Stopped (mark played, recounts)
/// waits on this, not on `stop()`.
pub struct FinalDelivery {
    settled: oneshot::Receiver<()>,
    cancel: Arc<Cancel>,
}

impl FinalDelivery {
    /// Returns whether a newer action cancelled the delivery (docs/07 Watch state, Ordering).
    pub async fn settled(self) -> bool {
        // The sender only ever drops, so an error is the settle signal.
        let _ = self.settled.await;
        self.cancel.state() == CancelState::Cancelled
    }
}

/// Whether a settling delivery may send: a pending mark pauses it, a newer action cancels it for good.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum CancelState {
    Active,
    Paused,
    Cancelled,
}

/// The inputs of a [`CancelState`], so overlapping pauses and a cancel can never disagree.
#[derive(Default)]
struct CancelInputs {
    pauses: usize,
    cancelled: bool,
}

impl CancelInputs {
    fn state(&self) -> CancelState {
        if self.cancelled {
            CancelState::Cancelled
        } else if self.pauses > 0 {
            CancelState::Paused
        } else {
            CancelState::Active
        }
    }
}

/// A settling delivery's state (docs/07 Watch state, Ordering); every change wakes its waiters.
struct Cancel {
    inputs: watch::Sender<CancelInputs>,
    /// Whether a final request has begun, so any further one is a retry.
    attempted: AtomicBool,
}

impl Cancel {
    fn new() -> Self {
        Self {
            inputs: watch::channel(CancelInputs::default()).0,
            attempted: AtomicBool::new(false),
        }
    }

    fn state(&self) -> CancelState {
        self.inputs.borrow().state()
    }

    fn pause(&self) {
        self.inputs.send_modify(|inputs| inputs.pauses += 1);
    }

    fn resume(&self) {
        self.inputs
            .send_modify(|inputs| inputs.pauses = inputs.pauses.saturating_sub(1));
    }

    fn cancel(&self) {
        self.inputs.send_modify(|inputs| inputs.cancelled = true);
    }

    /// Resolves once the state satisfies `wanted`.
    async fn wait_for(&self, wanted: impl Fn(CancelState) -> bool) {
        let mut inputs = self.inputs.subscribe();
        // The sender lives in `self`, so the channel cannot close under this wait.
        let _ = inputs.wait_for(|inputs| wanted(inputs.state())).await;
    }
}

/// docs/07 Watch state, docs/18 §2.1: per canonical item id, the sessions still playing, the
/// stopped ones whose final Stopped is unsettled, and the final Stopped attempts on the wire;
/// refcounted so two holds release independently.
#[derive(Default)]
struct HoldCounts {
    live: usize,
    settling: Vec<Arc<Cancel>>,
    attempts: usize,
}

impl HoldCounts {
    /// Cancels the deliveries that already attempted: their next attempt is a retry.
    fn cancel_retries(&self) {
        self.settling
            .iter()
            .filter(|cancel| cancel.attempted.load(Ordering::Acquire))
            .for_each(|cancel| cancel.cancel());
    }

    fn is_empty(&self) -> bool {
        self.live == 0 && self.settling.is_empty() && self.attempts == 0
    }
}

fn pending_registry() -> &'static Mutex<HashMap<String, HoldCounts>> {
    static REGISTRY: OnceLock<Mutex<HashMap<String, HoldCounts>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lock_registry() -> std::sync::MutexGuard<'static, HashMap<String, HoldCounts>> {
    pending_registry()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// The one place held counts are released: mutates the item's counts and removes the entry once
/// nothing holds it, so no path can leave an emptied item pending.
fn release(item_id: &str, change: impl FnOnce(&mut HoldCounts)) {
    let mut registry = lock_registry();
    if let Some(counts) = registry.get_mut(item_id) {
        change(counts);
        if counts.is_empty() {
            registry.remove(item_id);
        }
    }
}

/// The registry key for an item id: lowercase, dashes stripped, so every spelling of one id matches.
fn canonical_id(id: &str) -> String {
    id.chars()
        .filter(|c| *c != '-')
        .flat_map(char::to_lowercase)
        .collect()
}

/// Wakes [`supersede`] whenever an item's final Stopped attempts on the wire reach zero.
fn attempts_notify() -> &'static tokio::sync::Notify {
    static NOTIFY: OnceLock<tokio::sync::Notify> = OnceLock::new();
    NOTIFY.get_or_init(tokio::sync::Notify::new)
}

/// Keeps one item id in [`pending_local_item_ids`] from construction until drop.
struct PendingHold {
    item_id: String,
    /// Set once the session has stopped and its final Stopped is unsettled.
    settling: Option<Arc<Cancel>>,
}

impl PendingHold {
    /// A new live hold on an item cancels the retries of that item's older settling deliveries:
    /// the newest action wins (docs/07 Watch state, Ordering).
    fn new(item_id: &str) -> Self {
        let item_id = canonical_id(item_id);
        {
            let mut registry = lock_registry();
            let counts = registry.entry(item_id.clone()).or_default();
            counts.cancel_retries();
            counts.live += 1;
        }
        Self {
            item_id,
            settling: None,
        }
    }

    /// The session has stopped: from here the hold is a cancellable settling delivery.
    fn begin_settling(&mut self) -> Arc<Cancel> {
        if let Some(cancel) = &self.settling {
            return cancel.clone();
        }
        let cancel = Arc::new(Cancel::new());
        if let Some(counts) = lock_registry().get_mut(&self.item_id) {
            counts.live -= 1;
            counts.settling.push(cancel.clone());
        }
        self.settling = Some(cancel.clone());
        cancel
    }

    fn cancel(&self) -> Option<&Arc<Cancel>> {
        self.settling.as_ref()
    }

    /// Counts a final request on the wire unless the delivery is paused or cancelled; checked
    /// under the registry lock so [`supersede`] never misses an attempt that then starts.
    fn begin_attempt(&self) -> Begin {
        let Some(cancel) = self.settling.as_ref() else {
            return Begin::Cancelled;
        };
        let mut registry = lock_registry();
        match cancel.state() {
            CancelState::Cancelled => return Begin::Cancelled,
            CancelState::Paused => return Begin::Paused,
            CancelState::Active => {}
        }
        let Some(counts) = registry.get_mut(&self.item_id) else {
            return Begin::Cancelled;
        };
        counts.attempts += 1;
        cancel.attempted.store(true, Ordering::Release);
        Begin::Go(Attempt {
            item_id: self.item_id.clone(),
        })
    }
}

impl Drop for PendingHold {
    fn drop(&mut self) {
        release(&self.item_id, |counts| match &self.settling {
            Some(cancel) => counts.settling.retain(|c| !Arc::ptr_eq(c, cancel)),
            None => counts.live -= 1,
        });
    }
}

/// Whether a final request may go out now (see [`PendingHold::begin_attempt`]).
enum Begin {
    Go(Attempt),
    Paused,
    Cancelled,
}

/// A final request on the wire.
struct Attempt {
    item_id: String,
}

impl Drop for Attempt {
    fn drop(&mut self) {
        release(&self.item_id, |counts| counts.attempts -= 1);
        attempts_notify().notify_waiters();
    }
}

/// docs/07 Watch state: canonical (lowercase, undashed) ids held by a live session or an unsettled
/// final delivery, so a played-state sweep never reverts a local outcome the server hasn't seen.
pub fn pending_local_item_ids() -> HashSet<String> {
    lock_registry().keys().cloned().collect()
}

/// A pause on the settling deliveries of some items, held by the action that supersedes them.
#[must_use = "dropping the guard resumes the paused deliveries; commit it once the action succeeded"]
pub struct Superseded {
    paused: Vec<Arc<Cancel>>,
}

impl Superseded {
    /// The superseding action succeeded: the paused deliveries are cancelled for good.
    pub fn commit(mut self) {
        for cancel in std::mem::take(&mut self.paused) {
            cancel.cancel();
        }
    }
}

impl Drop for Superseded {
    /// Dropped uncommitted, the action failed: the paused deliveries resume.
    fn drop(&mut self) {
        for cancel in &self.paused {
            cancel.resume();
        }
    }
}

/// docs/07 Watch state, Ordering: pauses every settling final delivery of `item_ids` now, so none
/// starts another request, and resolves to a [`Superseded`] guard once none is on the wire; the
/// action that supersedes them then reaches the server after them, and commits the guard on
/// success. Live sessions are never affected, and a request on the wire is bounded by the HTTP
/// client's request timeout.
pub fn supersede(item_ids: &[&str]) -> impl Future<Output = Superseded> {
    let ids: Vec<String> = item_ids.iter().map(|id| canonical_id(id)).collect();
    let guard = {
        let registry = lock_registry();
        let paused: Vec<Arc<Cancel>> = ids
            .iter()
            .filter_map(|id| registry.get(id))
            .flat_map(|counts| counts.settling.iter().cloned())
            .collect();
        paused.iter().for_each(|cancel| cancel.pause());
        Superseded { paused }
    };
    async move {
        loop {
            let notified = attempts_notify().notified();
            tokio::pin!(notified);
            // Enabled before the check so a decrement between check and await still wakes us.
            notified.as_mut().enable();
            let on_the_wire = {
                let registry = lock_registry();
                ids.iter()
                    .any(|id| registry.get(id).is_some_and(|counts| counts.attempts > 0))
            };
            if !on_the_wire {
                return guard;
            }
            notified.await;
        }
    }
}

/// `#[must_use]` catches a `ReportingSession` created and discarded without `.stop()` at compile
/// time; the runtime case (dropped without `.stop()`) is caught by [`Drop`] instead, which logs a
/// warning.
#[must_use = "a ReportingSession must be `.stop()`-ed to send the final Stopped \
              report; dropping it without stopping skips that report (a warning \
              is logged when this happens)"]
pub struct ReportingSession {
    cmd_tx: mpsc::UnboundedSender<Cmd>,
    /// Latest-wins slot for position ticks: a `watch` naturally coalesces a burst of updates
    /// (e.g. queued while `run()` is blocked in `send_retrying`) down to the newest value instead
    /// of the actor replaying every stale one.
    position_tx: watch::Sender<i64>,
    /// Lets `stop()` take ownership of final delivery if the actor is stuck in a request or retry
    /// sleep past the UI's quit grace window.
    task: tokio::task::AbortHandle,
    sink: Arc<dyn ReportSink>,
    ctx: ReportContext,
    /// Set once `.stop()` has run, so `Drop` knows not to warn.
    stopped: bool,
    /// Whether the first `Start` report was ever delivered, shared with `run()`'s task so `stop()`
    /// can read it directly -- a lone Stopped has no `Sessions/Playing` record to close if Start
    /// never landed (see [`flush_with_cap`]).
    start_acked: Arc<AtomicBool>,
    /// Final Stopped delivery owner, shared with `run()` (see [`FINAL_UNCLAIMED`]).
    final_owner: Arc<AtomicU8>,
    /// Held for the session's lifetime; `stop()` moves it into the final [`Delivery`].
    hold: Option<PendingHold>,
}

impl ReportingSession {
    pub fn start(client: JellyfinClient, ctx: ReportContext) -> Self {
        Self::start_with_sink(Arc::new(client), ctx)
    }

    pub(crate) fn start_with_sink(sink: Arc<dyn ReportSink>, ctx: ReportContext) -> Self {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let (position_tx, position_rx) = watch::channel(0i64);
        let start_acked = Arc::new(AtomicBool::new(false));
        let final_owner = Arc::new(AtomicU8::new(FINAL_UNCLAIMED));
        let hold = PendingHold::new(&ctx.item_id);
        let task = tokio::spawn(run(
            sink.clone(),
            ctx.clone(),
            cmd_rx,
            position_rx,
            start_acked.clone(),
            final_owner.clone(),
        ));
        Self {
            cmd_tx,
            position_tx,
            task: task.abort_handle(),
            sink,
            ctx,
            stopped: false,
            start_acked,
            final_owner,
            hold: Some(hold),
        }
    }

    pub fn on_position(&mut self, ticks: i64) {
        let _ = self.position_tx.send(ticks);
    }

    pub fn on_pause(&mut self, paused: bool) {
        let _ = self.cmd_tx.send(Cmd::Pause(paused));
    }

    /// Discards a session superseded before playback became active. Deliberately doesn't emit
    /// `Stopped(0)` (could overwrite a newer session's resume point); logs the trade-off since
    /// `stopped = true` suppresses the `Drop` warning.
    pub fn abandon(mut self) {
        self.stopped = true;
        self.task.abort();
        tracing::info!(
            item_id = %self.ctx.item_id,
            play_session_id = %self.ctx.play_session_id,
            "reporting session abandoned (superseded before playback became \
             active); any Start already sent will age out server-side"
        );
    }

    /// The last position handed to [`Self::on_position`] (0 before any), for a stop that has no
    /// fresher position of its own.
    pub fn last_position(&self) -> i64 {
        *self.position_tx.borrow()
    }

    /// Read-only view of this session's report identifiers, so the app's quit path can persist a
    /// pending Stopped report without this crate knowing about paths or serialization.
    pub fn context(&self) -> &ReportContext {
        &self.ctx
    }

    /// Final position report; consumes the session. The item becomes a settling delivery, and the
    /// actor is handed the report, when this is called, not when the future is first polled
    /// (docs/07 Watch state, Ordering). The actor delivers it, or, if it hasn't begun within
    /// [`STOP_ACK_TIMEOUT`], it's aborted and the report goes to the shared [`FlushQueue`] (which
    /// re-establishes Start first if never acked). Doesn't survive process exit. The future
    /// resolves once delivery is handed off; the [`FinalDelivery`] settles when it lands.
    pub fn stop(mut self, ticks: i64) -> impl Future<Output = FinalDelivery> {
        self.stopped = true;
        let (completion_tx, completion_rx) = oneshot::channel();
        let (delivered_tx, delivered_rx) = oneshot::channel();
        let mut hold = self.hold.take().expect("hold set until stop");
        let cancel = hold.begin_settling();
        let delivered: DeliverySlot = Arc::new(Mutex::new(Some(Delivery {
            hold,
            _settled: delivered_tx,
        })));
        let finished = FinalDelivery {
            settled: delivered_rx,
            cancel,
        };
        let sent = self
            .cmd_tx
            .send(Cmd::Stop {
                ticks,
                completion: completion_tx,
                delivered: delivered.clone(),
            })
            .is_ok();
        async move {
            if sent {
                if let Ok(Ok(())) = tokio::time::timeout(STOP_ACK_TIMEOUT, completion_rx).await {
                    return finished;
                }
                // The actor already sending the Stopped finishes it (and queues its own retry).
                if !claim_final(&self.final_owner, FINAL_BY_CALLER) {
                    return finished;
                }
                // Abort so a later Start/Progress retry can't land after the final report.
                self.task.abort();
            }
            // Bounded background retry, never inline (would hold `stop()` for a full request
            // timeout); also repairs a never-acked Start (see `flush_with_cap`).
            let report = build_report(
                &self.ctx,
                PlaybackReportKind::Stopped,
                ticks,
                DEFAULT_VOLUME,
                None,
                None,
            );
            flush_queue().enqueue(
                self.sink.clone(),
                self.ctx.clone(),
                self.start_acked.load(Ordering::Relaxed),
                report,
                take_delivery(&delivered),
            );
            finished
        }
    }
}

/// Actor-side final delivery. When Start was never acknowledged, a bare Stopped closes nothing
/// (Jellyfin ignores a Stopped for a `play_session_id` it never saw a Start for), so Start is sent
/// first; if that fails the ordered pair goes to the bounded [`FlushQueue`].
async fn send_final_stopped(
    sink: &Arc<dyn ReportSink>,
    ctx: &ReportContext,
    start_acked: bool,
    ticks: i64,
    delivered: Option<Delivery>,
) {
    let report = build_report(
        ctx,
        PlaybackReportKind::Stopped,
        ticks,
        DEFAULT_VOLUME,
        None,
        None,
    );
    if !start_acked {
        let start_report = build_report(
            ctx,
            PlaybackReportKind::Start,
            ticks,
            DEFAULT_VOLUME,
            None,
            None,
        );
        match send_final_request(sink, delivered.as_ref(), start_report).await {
            Sent::Done(Ok(())) => {}
            Sent::Cancelled => return,
            Sent::Paused | Sent::Done(Err(_)) => {
                tracing::warn!(
                    "start-repair before final stopped did not land inline; queuing \
                     ordered pair for bounded background retry"
                );
                flush_queue().enqueue(sink.clone(), ctx.clone(), false, report, delivered);
                return;
            }
        }
    }
    match send_final_request(sink, delivered.as_ref(), report.clone()).await {
        Sent::Done(Ok(())) | Sent::Cancelled => {}
        Sent::Paused | Sent::Done(Err(_)) => {
            tracing::warn!(
                "stopped report did not land inline; queuing for bounded background retry"
            );
            // Start is established by this point, so the flush only needs to deliver the Stopped.
            flush_queue().enqueue(sink.clone(), ctx.clone(), true, report, delivered);
        }
    }
}

impl Drop for ReportingSession {
    /// A session dropped without `.stop()` leaves the server's `Sessions/Playing` state dangling;
    /// this logs a warning instead of failing silently.
    fn drop(&mut self) {
        if !self.stopped {
            tracing::warn!(
                item_id = %self.ctx.item_id,
                play_session_id = %self.ctx.play_session_id,
                "ReportingSession dropped without calling stop() -- the final \
                 Stopped playback report was never sent for this session"
            );
        }
    }
}

fn build_report(
    ctx: &ReportContext,
    kind: PlaybackReportKind,
    position_ticks: i64,
    volume_level: u8,
    audio_stream_index: Option<i32>,
    subtitle_stream_index: Option<i32>,
) -> PlaybackReport {
    PlaybackReport {
        kind,
        item_id: ctx.item_id.clone(),
        media_source_id: ctx.media_source_id.clone(),
        position_ticks,
        is_paused: false,
        volume_level,
        audio_stream_index,
        subtitle_stream_index,
        play_session_id: ctx.play_session_id.clone(),
        play_method: ctx.play_method,
    }
}

/// The background actor: owns the authoritative playback state, the 10s progress ticker, and
/// immediate edge reports on pause/seek/track-change (seek reports debounced -- see
/// [`SEEK_DEBOUNCE`]).
async fn run(
    sink: Arc<dyn ReportSink>,
    ctx: ReportContext,
    mut cmd_rx: mpsc::UnboundedReceiver<Cmd>,
    mut position_rx: watch::Receiver<i64>,
    start_acked: Arc<AtomicBool>,
    final_owner: Arc<AtomicU8>,
) {
    let mut state = PlaybackState {
        position_ticks: 0,
        is_paused: false,
        audio_stream_index: None,
        subtitle_stream_index: None,
    };

    let start_ok = send_retrying(&sink, report_for(&ctx, &state, PlaybackReportKind::Start)).await;
    start_acked.store(start_ok, Ordering::Relaxed);

    let mut ticker = tokio::time::interval(PROGRESS_INTERVAL);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    ticker.tick().await; // first tick fires immediately; consume it

    // A detected seek (re)starts this deadline, so a burst of scrubbing collapses into one report
    // once quiet for SEEK_DEBOUNCE.
    let mut seek_report_deadline: Option<tokio::time::Instant> = None;

    loop {
        let sleep_until_seek_deadline = tokio::time::sleep_until(
            seek_report_deadline.unwrap_or_else(tokio::time::Instant::now),
        );

        tokio::select! {
            _ = ticker.tick() => {
                send_retrying(&sink, report_for(&ctx, &state, PlaybackReportKind::Progress)).await;
            }
            () = sleep_until_seek_deadline, if seek_report_deadline.is_some() => {
                seek_report_deadline = None;
                send_retrying(&sink, report_for(&ctx, &state, PlaybackReportKind::Progress)).await;
                ticker.reset();
            }
            changed = position_rx.changed() => {
                if changed.is_ok() {
                    apply_pending_position(&mut position_rx, &mut state, &mut seek_report_deadline);
                }
                // Err means `ReportingSession` was dropped; `cmd_rx` closing ends the loop below.
            }
            cmd = cmd_rx.recv() => {
                // A position sent just before this command must land first, or the command's own
                // report (Pause/TrackChange) would carry a stale `position_ticks` -- the command
                // channel and the position watch have no ordering guarantee between them.
                if position_rx.has_changed().unwrap_or(false) {
                    apply_pending_position(&mut position_rx, &mut state, &mut seek_report_deadline);
                }
                match cmd {
                    Some(Cmd::Pause(paused)) => {
                        if state.is_paused != paused {
                            state.is_paused = paused;
                            send_retrying(&sink, report_for(&ctx, &state, PlaybackReportKind::Progress)).await;
                            ticker.reset();
                        }
                    }
                    Some(Cmd::Stop { ticks, completion, delivered }) => {
                        if !claim_final(&final_owner, FINAL_BY_ACTOR) {
                            break;
                        }
                        send_final_stopped(
                            &sink,
                            &ctx,
                            start_acked.load(Ordering::Relaxed),
                            ticks,
                            take_delivery(&delivered),
                        ).await;
                        let _ = completion.send(());
                        break;
                    }
                    None => break,
                }
            }
        }
    }
}

/// Applies the latest value already sitting in the position watch to `state`, detecting a seek
/// edge exactly as the `changed` select branch does. Shared so draining the watch ahead of a
/// `Cmd` (to avoid that command's report carrying a stale position) doesn't duplicate the logic.
fn apply_pending_position(
    position_rx: &mut watch::Receiver<i64>,
    state: &mut PlaybackState,
    seek_report_deadline: &mut Option<tokio::time::Instant>,
) {
    let ticks = *position_rx.borrow_and_update();
    let jump = (ticks - state.position_ticks).abs();
    state.position_ticks = ticks;
    // A >2s jump is a seek edge, debounced by (re)arming the quiet-window deadline so a scrub-bar
    // drag coalesces into one report.
    if jump > seek_threshold_ticks() {
        *seek_report_deadline = Some(tokio::time::Instant::now() + SEEK_DEBOUNCE);
    }
}

fn seek_threshold_ticks() -> i64 {
    // Jellyfin ticks are 100ns units; 2s of drift is beyond normal polling jitter but inside a
    // deliberate seek.
    2 * 10_000_000
}

fn report_for(
    ctx: &ReportContext,
    state: &PlaybackState,
    kind: PlaybackReportKind,
) -> PlaybackReport {
    PlaybackReport {
        kind,
        item_id: ctx.item_id.clone(),
        media_source_id: ctx.media_source_id.clone(),
        position_ticks: state.position_ticks,
        is_paused: state.is_paused,
        volume_level: DEFAULT_VOLUME,
        audio_stream_index: state.audio_stream_index,
        subtitle_stream_index: state.subtitle_stream_index,
        play_session_id: ctx.play_session_id.clone(),
        play_method: ctx.play_method,
    }
}

/// Bounded retry for start/progress reports -- not worth retrying forever since a fresher one
/// follows within 10s. Returns whether delivery ever succeeded, so `run()` can track whether the
/// first `Start` landed.
async fn send_retrying(sink: &Arc<dyn ReportSink>, report: PlaybackReport) -> bool {
    let mut backoff = Backoff::new(RETRY_BASE, RETRY_CAP);
    for attempt in 0..TRANSIENT_REPORT_MAX_ATTEMPTS {
        match sink.report(report.clone()).await {
            Ok(()) => return true,
            Err(err) if attempt + 1 == TRANSIENT_REPORT_MAX_ATTEMPTS => {
                tracing::warn!(
                    ?err,
                    "giving up on playback report after {TRANSIENT_REPORT_MAX_ATTEMPTS} attempts"
                );
                return false;
            }
            Err(_) => tokio::time::sleep(backoff.next_delay()).await,
        }
    }
    false
}

/// Delivers exactly one background retry loop per in-flight `play_session_id` for the final Stopped
/// report: caps retry duration (gives up after [`FLUSH_MAX_DURATION`]) and dedups by
/// `play_session_id`. A lazily-initialized process-wide singleton (see [`flush_queue`]) since
/// `ReportingSession` has no shared owner.
struct FlushQueue {
    /// `play_session_id`s whose Stopped report is being flushed.
    in_flight: Mutex<HashSet<String>>,
}

impl FlushQueue {
    fn new() -> Self {
        Self {
            in_flight: Mutex::new(HashSet::new()),
        }
    }

    /// Queues `report`'s delivery, unless a flush for the same `play_session_id` is already in
    /// flight (a no-op). `start_acked: false` means the flush must resend Start before `report`
    /// (see [`flush_with_cap`]). `delivered` drops when this flush ends.
    fn enqueue(
        &'static self,
        sink: Arc<dyn ReportSink>,
        ctx: ReportContext,
        start_acked: bool,
        report: PlaybackReport,
        delivered: Option<Delivery>,
    ) {
        let session_id = report.play_session_id.clone();
        let already_in_flight = {
            let mut in_flight = self.in_flight.lock().expect("flush queue lock");
            !in_flight.insert(session_id.clone())
        };
        if already_in_flight {
            tracing::debug!(
                play_session_id = %session_id,
                "stopped-report flush already in-flight for this session; \
                 dropping duplicate enqueue rather than spawning a second task"
            );
            return;
        }

        tokio::spawn(async move {
            flush_with_cap(&sink, &ctx, start_acked, &report, delivered.as_ref()).await;
            self.in_flight
                .lock()
                .expect("flush queue lock")
                .remove(&session_id);
            drop(delivered);
        });
    }
}

fn flush_queue() -> &'static FlushQueue {
    static QUEUE: OnceLock<FlushQueue> = OnceLock::new();
    QUEUE.get_or_init(FlushQueue::new)
}

/// Capped-backoff retry loop backing [`FlushQueue`]: retries until success or
/// [`FLUSH_MAX_DURATION`] elapses, then gives up loudly. When `start_acked == false`, `Start` is
/// resent first (Jellyfin ignores a `Stopped` for a `play_session_id` it never saw a `Start` for),
/// under the same deadline. A paused `delivery` (docs/07 Watch state, Ordering) waits to resume; a
/// cancelled one ends the loop before its next attempt, mid-backoff included.
async fn flush_with_cap(
    sink: &Arc<dyn ReportSink>,
    ctx: &ReportContext,
    start_acked: bool,
    report: &PlaybackReport,
    delivery: Option<&Delivery>,
) {
    let mut backoff = Backoff::new(RETRY_BASE, RETRY_CAP);
    let deadline = tokio::time::Instant::now() + FLUSH_MAX_DURATION;
    let mut start_delivered = start_acked;

    while !start_delivered {
        let start_report = build_report(
            ctx,
            PlaybackReportKind::Start,
            report.position_ticks,
            report.volume_level,
            report.audio_stream_index,
            report.subtitle_stream_index,
        );
        match send_final_request_waiting(sink, delivery, &start_report).await {
            None => return,
            Some(Ok(())) => start_delivered = true,
            Some(Err(err)) => {
                let now = tokio::time::Instant::now();
                if now >= deadline {
                    tracing::error!(
                        ?err,
                        play_session_id = %report.play_session_id,
                        max_duration = ?FLUSH_MAX_DURATION,
                        "giving up on final Stopped playback report after the \
                         retry cap elapsed while re-sending the never-acked \
                         Start report; the server will never see this \
                         session's stop position"
                    );
                    return;
                }
                let delay = backoff.next_delay().min(deadline - now);
                if !sleep_unless_cancelled(delay, delivery).await {
                    return;
                }
            }
        }
    }

    loop {
        match send_final_request_waiting(sink, delivery, report).await {
            None | Some(Ok(())) => return,
            Some(Err(err)) => {
                let now = tokio::time::Instant::now();
                if now >= deadline {
                    tracing::error!(
                        ?err,
                        play_session_id = %report.play_session_id,
                        max_duration = ?FLUSH_MAX_DURATION,
                        "giving up on final Stopped playback report after the \
                         retry cap elapsed; the server will never see this \
                         session's stop position"
                    );
                    return;
                }
                let delay = backoff.next_delay().min(deadline - now);
                if !sleep_unless_cancelled(delay, delivery).await {
                    return;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use tokio::sync::Notify;

    #[derive(Default)]
    struct FakeSink {
        calls: Mutex<Vec<PlaybackReport>>,
        /// Number of leading calls to fail before succeeding forever after.
        fail_first: Mutex<u32>,
        notify: Notify,
    }

    impl FakeSink {
        fn new(fail_first: u32) -> Arc<Self> {
            Arc::new(Self {
                fail_first: Mutex::new(fail_first),
                ..Default::default()
            })
        }

        fn calls(&self) -> Vec<PlaybackReport> {
            self.calls.lock().expect("lock").clone()
        }
    }

    impl ReportSink for FakeSink {
        fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
            Box::pin(async move {
                self.calls.lock().expect("lock").push(report);
                self.notify.notify_waiters();
                let mut remaining = self.fail_first.lock().expect("lock");
                if *remaining > 0 {
                    *remaining -= 1;
                    Err(ApiError::Transport("simulated failure".into()))
                } else {
                    Ok(())
                }
            })
        }
    }

    struct BlockingStartSink {
        calls: Mutex<Vec<PlaybackReport>>,
        start_entered: AtomicBool,
        release_start: Notify,
    }

    struct FirstRequestBlocksSink {
        calls: Mutex<Vec<PlaybackReport>>,
        request_count: std::sync::atomic::AtomicUsize,
    }

    impl FirstRequestBlocksSink {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                calls: Mutex::new(Vec::new()),
                request_count: std::sync::atomic::AtomicUsize::new(0),
            })
        }

        fn calls(&self) -> Vec<PlaybackReport> {
            self.calls.lock().expect("lock").clone()
        }
    }

    impl ReportSink for FirstRequestBlocksSink {
        fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
            Box::pin(async move {
                self.calls.lock().expect("lock").push(report);
                if self.request_count.fetch_add(1, Ordering::AcqRel) == 0 {
                    std::future::pending::<()>().await;
                }
                Ok(())
            })
        }
    }

    impl BlockingStartSink {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                calls: Mutex::new(Vec::new()),
                start_entered: AtomicBool::new(false),
                release_start: Notify::new(),
            })
        }

        fn calls(&self) -> Vec<PlaybackReport> {
            self.calls.lock().expect("lock").clone()
        }
    }

    impl ReportSink for BlockingStartSink {
        fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
            Box::pin(async move {
                let is_start = matches!(report.kind, PlaybackReportKind::Start);
                self.calls.lock().expect("lock").push(report);
                if is_start {
                    self.start_entered.store(true, Ordering::Release);
                    self.release_start.notified().await;
                }
                Ok(())
            })
        }
    }

    /// A fresh item id per call: the hold registry is process-wide, so tests must not share items.
    fn unique_item() -> String {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        format!("item-{}", NEXT.fetch_add(1, Ordering::Relaxed))
    }

    fn ctx() -> ReportContext {
        ReportContext {
            item_id: unique_item(),
            media_source_id: "source".into(),
            play_session_id: "session".into(),
            play_method: jellyfin_api::ReportPlayMethod::DirectPlay,
        }
    }

    /// Like [`ctx`], with a caller-chosen `play_session_id` so tests exercising the process-wide
    /// [`FlushQueue`] singleton don't collide.
    fn ctx_with_session(play_session_id: &str) -> ReportContext {
        ReportContext {
            item_id: unique_item(),
            media_source_id: "source".into(),
            play_session_id: play_session_id.into(),
            play_method: jellyfin_api::ReportPlayMethod::DirectPlay,
        }
    }

    #[tokio::test(start_paused = true)]
    async fn sends_start_report_immediately() {
        let sink = FakeSink::new(0);
        let session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;

        let calls = sink.calls();
        assert_eq!(calls.len(), 1);
        assert!(matches!(calls[0].kind, PlaybackReportKind::Start));

        session.stop(0).await;
    }

    #[tokio::test(start_paused = true)]
    async fn sends_progress_report_every_ten_seconds() {
        let sink = FakeSink::new(0);
        let session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 1); // start

        tokio::time::advance(Duration::from_secs(10)).await;
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 2);
        assert!(matches!(sink.calls()[1].kind, PlaybackReportKind::Progress));

        tokio::time::advance(Duration::from_secs(10)).await;
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 3);

        session.stop(0).await;
    }

    #[tokio::test(start_paused = true)]
    async fn pause_sends_immediate_edge_report() {
        let sink = FakeSink::new(0);
        let mut session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 1);

        session.on_pause(true);
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        let calls = sink.calls();
        assert_eq!(calls.len(), 2);
        assert!(calls[1].is_paused);

        session.stop(0).await;
    }

    /// Pins: a position sent immediately before an edge command (no intervening await) is applied
    /// before that command's own report, even though the position watch and the `Cmd` channel have
    /// no ordering guarantee between them -- see `apply_pending_position`.
    #[tokio::test(start_paused = true)]
    async fn a_position_sent_just_before_pause_is_reflected_in_the_pause_report() {
        let sink = FakeSink::new(0);
        let mut session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 1);

        session.on_position(123 * 10_000_000);
        session.on_pause(true);
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        let calls = sink.calls();
        let pause_report = calls
            .iter()
            .find(|r| r.is_paused)
            .expect("expected a paused report");
        assert_eq!(
            pause_report.position_ticks,
            123 * 10_000_000,
            "the pause report must carry the position sent just before it, not a stale value"
        );

        session.stop(123 * 10_000_000).await;
    }

    #[tokio::test(start_paused = true)]
    async fn large_position_jump_is_reported_as_seek_edge_after_debounce_quiet_window() {
        let sink = FakeSink::new(0);
        let mut session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 1);

        // 30s jump (Jellyfin ticks are 100ns units).
        session.on_position(30 * 10_000_000);
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        // Pins: not reported yet -- a seek report only fires once quiet for SEEK_DEBOUNCE.
        assert_eq!(
            sink.calls().len(),
            1,
            "seek report must be debounced, not sent immediately"
        );

        tokio::time::advance(Duration::from_millis(600)).await;
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        let calls = sink.calls();
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[1].position_ticks, 30 * 10_000_000);

        session.stop(0).await;
    }

    #[tokio::test(start_paused = true)]
    async fn small_position_updates_do_not_spam_reports() {
        let sink = FakeSink::new(0);
        let mut session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;

        session.on_position(1_000_000); // 100ms — not a seek
        tokio::task::yield_now().await;
        tokio::time::advance(Duration::from_millis(600)).await;
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 1); // no edge report fired

        session.stop(0).await;
    }

    /// Pins: a burst of rapid large jumps (a scrub-bar drag) coalesces into exactly one report,
    /// reflecting the final position, once quiet.
    #[tokio::test(start_paused = true)]
    async fn rapid_scrub_storm_coalesces_into_a_single_debounced_report() {
        let sink = FakeSink::new(0);
        let mut session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;
        assert_eq!(sink.calls().len(), 1);

        // Several large jumps in quick succession, simulating a scrub-bar drag.
        for seconds in [10_i64, 20, 5, 40, 33] {
            session.on_position(seconds * 10_000_000);
            tokio::task::yield_now().await;
            tokio::time::advance(Duration::from_millis(100)).await;
            tokio::task::yield_now().await;
        }
        assert_eq!(
            sink.calls().len(),
            1,
            "a burst of seeks within the debounce window must not produce a report per jump"
        );

        // Let the window finally go quiet.
        tokio::time::advance(Duration::from_millis(600)).await;
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        let calls = sink.calls();
        assert_eq!(
            calls.len(),
            2,
            "the whole scrub burst must coalesce into exactly one report"
        );
        assert_eq!(
            calls[1].position_ticks,
            33 * 10_000_000,
            "the coalesced report must reflect the final position, not an intermediate one"
        );

        session.stop(0).await;
    }

    /// Pins: positions queued while the sink is still busy (the actor blocked delivering Start)
    /// coalesce to the newest value instead of the actor replaying every stale tick on recovery.
    /// Uses [`BlockingStartSink`] rather than a timed retry so the outcome doesn't depend on
    /// jittered backoff timing racing the progress ticker.
    #[tokio::test(start_paused = true)]
    async fn positions_enqueued_during_an_outage_coalesce_to_the_latest_on_recovery() {
        let sink = BlockingStartSink::new();
        let mut session = ReportingSession::start_with_sink(sink.clone(), ctx());
        while !sink.start_entered.load(Ordering::Acquire) {
            tokio::task::yield_now().await;
        }

        // Enqueued while `run()` is still blocked delivering Start, before the actor ever reaches
        // the select loop that would observe them.
        for seconds in [5_i64, 12, 40, 999] {
            session.on_position(seconds * 10_000_000);
        }

        // Let Start complete and the actor reach the select loop.
        sink.release_start.notify_one();
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        // Let the seek debounce window elapse so the coalesced position is reported.
        tokio::time::advance(Duration::from_millis(600)).await;
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        let progress_reports: Vec<_> = sink
            .calls()
            .into_iter()
            .filter(|r| matches!(r.kind, PlaybackReportKind::Progress))
            .collect();
        assert_eq!(
            progress_reports.len(),
            1,
            "queued positions must coalesce into a single report, not one per stale tick"
        );
        assert_eq!(
            progress_reports[0].position_ticks,
            999 * 10_000_000,
            "the coalesced report must reflect the newest position, not a stale intermediate one"
        );

        session.stop(999 * 10_000_000).await;
    }

    #[tokio::test(start_paused = true)]
    async fn stop_always_sends_final_stopped_report() {
        let sink = FakeSink::new(0);
        let session = ReportingSession::start_with_sink(sink.clone(), ctx());
        tokio::task::yield_now().await;

        session.stop(12345).await;
        let calls = sink.calls();
        let last = calls.last().expect("test assertion");
        assert!(matches!(last.kind, PlaybackReportKind::Stopped));
        assert_eq!(last.position_ticks, 12345);
    }

    #[tokio::test]
    async fn stop_waits_for_start_before_sending_stopped() {
        let sink = BlockingStartSink::new();
        let session = ReportingSession::start_with_sink(sink.clone(), ctx());
        while !sink.start_entered.load(Ordering::Acquire) {
            tokio::task::yield_now().await;
        }

        let stop_task = tokio::spawn(async move { session.stop(42).await });
        tokio::task::yield_now().await;
        assert!(
            sink.calls()
                .iter()
                .all(|r| matches!(r.kind, PlaybackReportKind::Start)),
            "Stopped must not overtake an in-flight Start report"
        );

        // `notify_one` retains a permit if not yet polled, unlike `notify_waiters`.
        sink.release_start.notify_one();
        stop_task.await.expect("stop task");
        let calls = sink.calls();
        assert!(matches!(calls.as_slice(), [start, stopped]
            if matches!(start.kind, PlaybackReportKind::Start)
                && matches!(stopped.kind, PlaybackReportKind::Stopped)));
        assert_eq!(calls[1].position_ticks, 42);
    }

    #[tokio::test]
    async fn stop_timeout_hands_final_report_to_flush_queue() {
        let sink = FirstRequestBlocksSink::new();
        let session = ReportingSession::start_with_sink(sink.clone(), ctx_with_session("timeout"));
        while sink.calls().is_empty() {
            tokio::task::yield_now().await;
        }

        session.stop(777).await;
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }

        let calls = sink.calls();
        let stopped = calls
            .iter()
            .find(|report| matches!(report.kind, PlaybackReportKind::Stopped))
            .expect("timeout must enqueue a final Stopped report");
        assert_eq!(stopped.position_ticks, 777);
        let stopped_index = calls
            .iter()
            .position(|report| matches!(report.kind, PlaybackReportKind::Stopped))
            .expect("stopped index");
        assert!(
            calls[..stopped_index]
                .iter()
                .any(|report| matches!(report.kind, PlaybackReportKind::Start)),
            "FlushQueue must establish Start before final Stopped"
        );
    }

    /// A Stopped the server is slow to answer (it ends the transcode first) is sent once, never
    /// re-sent by `stop()` after its ack timeout.
    struct SlowStoppedSink {
        calls: Mutex<Vec<PlaybackReport>>,
    }

    impl ReportSink for SlowStoppedSink {
        fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
            Box::pin(async move {
                let slow = matches!(report.kind, PlaybackReportKind::Stopped);
                self.calls.lock().expect("lock").push(report);
                if slow {
                    tokio::time::sleep(STOP_ACK_TIMEOUT * 15).await;
                }
                Ok(())
            })
        }
    }

    #[tokio::test(start_paused = true)]
    async fn a_slow_final_stopped_is_sent_once() {
        let sink = Arc::new(SlowStoppedSink {
            calls: Mutex::new(Vec::new()),
        });
        let session =
            ReportingSession::start_with_sink(sink.clone(), ctx_with_session("slow-stop"));
        tokio::task::yield_now().await;

        session.stop(321).await;
        tokio::time::sleep(FLUSH_MAX_DURATION).await;

        let stopped: Vec<_> = sink
            .calls
            .lock()
            .expect("lock")
            .iter()
            .filter(|report| matches!(report.kind, PlaybackReportKind::Stopped))
            .map(|report| report.position_ticks)
            .collect();
        assert_eq!(stopped, vec![321]);
    }

    #[tokio::test(start_paused = true)]
    async fn final_delivery_settles_only_once_a_slow_stopped_lands() {
        let sink = Arc::new(SlowStoppedSink {
            calls: Mutex::new(Vec::new()),
        });
        let session =
            ReportingSession::start_with_sink(sink.clone(), ctx_with_session("slow-settle"));
        tokio::task::yield_now().await;

        let stopped_at = tokio::time::Instant::now();
        session.stop(321).await.settled().await;
        assert!(stopped_at.elapsed() >= STOP_ACK_TIMEOUT * 15);
    }

    #[tokio::test(start_paused = true)]
    async fn final_delivery_settles_only_once_a_retried_stopped_lands() {
        let sink = FakeSink::new(3);
        let session =
            ReportingSession::start_with_sink(sink.clone(), ctx_with_session("retry-settle"));
        tokio::task::yield_now().await;

        session.stop(999).await.settled().await;

        assert_eq!(*sink.fail_first.lock().expect("lock"), 0);
        let last = sink.calls().pop().expect("a report was sent");
        assert!(matches!(last.kind, PlaybackReportKind::Stopped));
        assert_eq!(last.position_ticks, 999);
    }

    #[tokio::test(start_paused = true)]
    async fn stop_reestablishes_never_acked_start_before_inline_stopped() {
        // Pins: after Start exhausts its attempts, inline final delivery still sends Start before
        // Stopped once the server recovers.
        let sink = FakeSink::new(TRANSIENT_REPORT_MAX_ATTEMPTS);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            ctx_with_session("start-repair-inline"),
        );
        // Drive through the failed Start attempts and their capped backoffs.
        for _ in 0..8 {
            tokio::time::advance(Duration::from_secs(4)).await;
            tokio::task::yield_now().await;
        }
        assert!(
            !session.start_acked.load(Ordering::Relaxed),
            "precondition: the initial Start must have given up unacked"
        );

        session.stop(555).await;

        let calls = sink.calls();
        let stopped_index = calls
            .iter()
            .position(|r| matches!(r.kind, PlaybackReportKind::Stopped))
            .expect("inline Stopped must be delivered");
        assert_eq!(calls[stopped_index].position_ticks, 555);
        assert!(
            stopped_index >= 1
                && matches!(calls[stopped_index - 1].kind, PlaybackReportKind::Start),
            "a never-acked Start must be re-established immediately before the inline Stopped"
        );
        assert_eq!(
            calls.len(),
            stopped_index + 1,
            "inline delivery succeeded, so no flush retry may add further reports"
        );
    }

    #[tokio::test(start_paused = true)]
    async fn stop_report_survives_transient_failures_via_background_retry() {
        // Fails the first 3 attempts, then succeeds; stop() must still guarantee delivery.
        let sink = FakeSink::new(3);
        let session =
            ReportingSession::start_with_sink(sink.clone(), ctx_with_session("bg-retry-session"));
        tokio::task::yield_now().await;

        // The actor is asleep in backoff, so stop() times out, aborts it, and enqueues onto the
        // FlushQueue.
        session.stop(999).await;

        // Advance past several backoff cycles to let the retry task succeed.
        for _ in 0..10 {
            tokio::time::advance(Duration::from_secs(70)).await;
            tokio::task::yield_now().await;
            tokio::task::yield_now().await;
        }

        let calls = sink.calls();
        let stopped: Vec<_> = calls
            .iter()
            .filter(|r| matches!(r.kind, PlaybackReportKind::Stopped))
            .collect();
        assert!(
            !stopped.is_empty(),
            "stopped report must eventually be delivered"
        );
        assert_eq!(stopped.last().expect("test assertion").position_ticks, 999);
    }

    // --- FlushQueue -------------------------------------------------

    /// A `ReportSink` whose `report()` blocks on a `Notify` until released, forcing two enqueued
    /// flushes to genuinely overlap.
    #[derive(Default)]
    struct GatedSink {
        calls: Mutex<Vec<PlaybackReport>>,
        gate: Notify,
    }

    impl ReportSink for GatedSink {
        fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
            Box::pin(async move {
                self.calls.lock().expect("lock").push(report);
                self.gate.notified().await;
                Ok(())
            })
        }
    }

    // --- pending_local_item_ids (docs/07 Watch state, docs/18 §2.1) -----

    const STOPPED_OK: u8 = 0;
    const STOPPED_FAILS: u8 = 1;
    const STOPPED_GATED: u8 = 2;

    /// Start always lands (the very first request hangs when `first_hangs`); Stopped follows
    /// `stopped_mode`.
    struct PendingSink {
        stopped_mode: std::sync::atomic::AtomicU8,
        first_hangs: bool,
        requests: std::sync::atomic::AtomicUsize,
        stopped_seen: std::sync::atomic::AtomicUsize,
        gate: Notify,
    }

    impl PendingSink {
        fn new(stopped_mode: u8, first_hangs: bool) -> Arc<Self> {
            Arc::new(Self {
                stopped_mode: std::sync::atomic::AtomicU8::new(stopped_mode),
                first_hangs,
                requests: std::sync::atomic::AtomicUsize::new(0),
                stopped_seen: std::sync::atomic::AtomicUsize::new(0),
                gate: Notify::new(),
            })
        }
    }

    impl ReportSink for PendingSink {
        fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
            Box::pin(async move {
                if self.requests.fetch_add(1, Ordering::AcqRel) == 0 && self.first_hangs {
                    std::future::pending::<()>().await;
                }
                if !matches!(report.kind, PlaybackReportKind::Stopped) {
                    return Ok(());
                }
                self.stopped_seen.fetch_add(1, Ordering::AcqRel);
                match self.stopped_mode.load(Ordering::Acquire) {
                    STOPPED_FAILS => Err(ApiError::Transport("simulated failure".into())),
                    STOPPED_GATED => {
                        self.gate.notified().await;
                        if self.stopped_mode.load(Ordering::Acquire) == STOPPED_FAILS {
                            return Err(ApiError::Transport("simulated failure".into()));
                        }
                        Ok(())
                    }
                    _ => Ok(()),
                }
            })
        }
    }

    /// The first `failing_starts` Starts fail, or the first hangs when that is 0; later ones wait on
    /// `gate`, everything else lands; records each kind.
    #[derive(Default)]
    struct StartGateSink {
        failing_starts: usize,
        reports: Mutex<Vec<&'static str>>,
        starts: std::sync::atomic::AtomicUsize,
        gate: Notify,
    }

    impl StartGateSink {
        fn kinds(&self) -> Vec<&'static str> {
            self.reports.lock().expect("lock").clone()
        }
    }

    impl ReportSink for StartGateSink {
        fn report(&self, report: PlaybackReport) -> BoxFuture<'_, Result<(), ApiError>> {
            Box::pin(async move {
                if matches!(report.kind, PlaybackReportKind::Start) {
                    self.reports.lock().expect("lock").push("Start");
                    let nth = self.starts.fetch_add(1, Ordering::AcqRel);
                    if self.failing_starts == 0 && nth == 0 {
                        std::future::pending::<()>().await;
                    }
                    if nth < self.failing_starts {
                        return Err(ApiError::Transport("simulated failure".into()));
                    }
                    self.gate.notified().await;
                } else {
                    let kind = match report.kind {
                        PlaybackReportKind::Stopped => "Stopped",
                        _ => "Progress",
                    };
                    self.reports.lock().expect("lock").push(kind);
                }
                Ok(())
            })
        }
    }

    fn pending_ctx(item_id: &str, play_session_id: &str) -> ReportContext {
        let mut ctx = ctx_with_session(play_session_id);
        ctx.item_id = item_id.into();
        ctx
    }

    fn is_pending(item_id: &str) -> bool {
        pending_local_item_ids().contains(&canonical_id(item_id))
    }

    async fn let_tasks_run() {
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
    }

    #[tokio::test(start_paused = true)]
    async fn item_is_pending_for_the_session_then_its_actor_inline_final_delivery_then_the_hold() {
        let item = "pending-actor-inline";
        let sink = PendingSink::new(STOPPED_GATED, false);
        let session =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "pending-actor-s"));
        let_tasks_run().await;
        assert!(
            is_pending(item),
            "before stop: the live session holds the item"
        );

        let delivery = session.stop(1).await;
        assert!(is_pending(item), "stop sent, not acked");

        sink.gate.notify_one();
        delivery.settled().await;
        assert!(!is_pending(item), "settled: the hold is released");
    }

    #[tokio::test(start_paused = true)]
    async fn item_stays_pending_through_a_caller_claimed_final_delivery() {
        let item = "pending-caller-claimed";
        let sink = PendingSink::new(STOPPED_GATED, true);
        let session =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "pending-caller-s"));
        let_tasks_run().await;
        assert!(is_pending(item));

        // The actor is stuck in Start, so stop() times out and enqueues the flush itself.
        let delivery = session.stop(1).await;
        let_tasks_run().await;
        assert!(is_pending(item), "stop sent, not acked");

        sink.gate.notify_one();
        delivery.settled().await;
        assert!(!is_pending(item), "settled: the hold is released");
    }

    #[tokio::test(start_paused = true)]
    async fn item_stays_pending_through_flush_queue_retries_and_after_they_land() {
        let item = "pending-flush-retry";
        let sink = PendingSink::new(STOPPED_FAILS, false);
        let session =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "pending-retry-s"));
        let_tasks_run().await;

        let delivery = session.stop(1).await;
        tokio::time::sleep(Duration::from_secs(5)).await;
        assert!(is_pending(item), "stop sent, retrying, not acked");

        sink.stopped_mode.store(STOPPED_OK, Ordering::Release);
        delivery.settled().await;
        assert!(!is_pending(item), "acked: the hold is released");
    }

    #[tokio::test(start_paused = true)]
    async fn item_is_released_with_the_hold_after_the_flush_gives_up() {
        let item = "pending-gave-up";
        let sink = PendingSink::new(STOPPED_FAILS, false);
        let session =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "pending-giveup-s"));
        let_tasks_run().await;

        let delivery = session.stop(1).await;
        assert!(is_pending(item));
        delivery.settled().await;
        assert!(!is_pending(item), "gave up: the hold is released");
    }

    #[tokio::test(start_paused = true)]
    async fn registry_keys_are_canonical_across_spellings() {
        assert_eq!(
            canonical_id("0A1B2C3D-0000-0000-0000-000000000001"),
            "0a1b2c3d000000000000000000000001"
        );
        let session = ReportingSession::start_with_sink(
            PendingSink::new(STOPPED_OK, false),
            pending_ctx("0A1B2C3D-0000-0000-0000-0000000000C2", "pending-canon-s"),
        );
        assert!(pending_local_item_ids().contains("0a1b2c3d0000000000000000000000c2"));
        session.abandon();
    }

    /// How many Stopped reports `sink` has seen.
    fn stopped_count(sink: &PendingSink) -> usize {
        sink.stopped_seen.load(Ordering::Acquire)
    }

    fn registry_holds(item_id: &str) -> bool {
        lock_registry().contains_key(&canonical_id(item_id))
    }

    #[tokio::test(start_paused = true)]
    async fn stop_makes_the_item_settling_before_the_future_is_polled() {
        let item = "settle-eager";
        let sink = PendingSink::new(STOPPED_GATED, false);
        let session =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "settle-eager-s"));
        let_tasks_run().await;
        assert_eq!(lock_registry()[&canonical_id(item)].live, 1);

        let stop = session.stop(1);
        {
            let registry = lock_registry();
            let counts = &registry[&canonical_id(item)];
            assert_eq!((counts.live, counts.settling.len()), (0, 1));
        }
        sink.gate.notify_one();
        stop.await.settled().await;
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn supersede_cancels_a_stopped_in_backoff_and_returns_at_once() {
        let item = "supersede-backoff";
        let sink = PendingSink::new(STOPPED_FAILS, false);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "supersede-backoff-s"),
        );
        let_tasks_run().await;
        let delivery = session.stop(1).await;
        tokio::time::sleep(Duration::from_secs(5)).await;
        let attempts = stopped_count(&sink);
        assert!(attempts >= 2, "the Stopped is retrying");

        let start = tokio::time::Instant::now();
        supersede(&[item]).await.commit();
        assert_eq!(start.elapsed(), Duration::ZERO);

        let_tasks_run().await;
        assert!(
            !registry_holds(item),
            "released without waiting out the backoff"
        );
        delivery.settled().await;
        tokio::time::sleep(Duration::from_secs(120)).await;
        assert_eq!(
            stopped_count(&sink),
            attempts,
            "no attempt after the supersede"
        );
        assert!(!is_pending(item));
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn supersede_waits_only_for_a_stopped_on_the_wire() {
        let item = "supersede-wire";
        let sink = PendingSink::new(STOPPED_GATED, false);
        let session =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "supersede-wire-s"));
        let_tasks_run().await;
        let delivery = session.stop(1).await;
        let_tasks_run().await;
        assert_eq!(stopped_count(&sink), 1);

        let mut waiting = Box::pin(supersede(&[item]));
        tokio::select! {
            _ = &mut waiting => panic!("supersede returned while the Stopped is on the wire"),
            () = tokio::time::sleep(Duration::from_secs(600)) => {}
        }
        sink.gate.notify_one();
        waiting.await.commit();
        delivery.settled().await;
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn a_replay_does_not_cancel_a_stopped_that_has_not_been_attempted() {
        let item = "supersede-replay-unsent";
        let sink = PendingSink::new(STOPPED_OK, false);
        let first = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "replay-unsent-first-s"),
        );
        let_tasks_run().await;

        let stop = first.stop(1);
        let second = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "replay-unsent-second-s"),
        );
        stop.await.settled().await;
        assert_eq!(stopped_count(&sink), 1, "the first attempt still goes out");
        second.abandon();
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn a_stopped_on_the_wire_that_fails_after_the_supersede_is_not_retried() {
        let item = "supersede-wire-fails";
        let sink = PendingSink::new(STOPPED_GATED, false);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "supersede-wire-fails-s"),
        );
        let_tasks_run().await;
        let delivery = session.stop(1).await;
        let_tasks_run().await;

        let waiting = supersede(&[item]);
        sink.stopped_mode.store(STOPPED_FAILS, Ordering::Release);
        sink.gate.notify_one();
        waiting.await.commit();
        delivery.settled().await;
        tokio::time::sleep(Duration::from_secs(120)).await;
        assert_eq!(stopped_count(&sink), 1);
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn a_stopped_superseded_before_its_first_attempt_is_never_sent() {
        let item = "supersede-unsent";
        let sink = PendingSink::new(STOPPED_OK, false);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "supersede-unsent-s"),
        );
        let_tasks_run().await;

        let stop = session.stop(1);
        supersede(&[item]).await.commit();
        let delivery = stop.await;
        delivery.settled().await;
        assert_eq!(stopped_count(&sink), 0);
        assert!(!registry_holds(item));
    }

    /// docs/07 Watch state, Ordering: a pending mark pauses the delivery (no attempt, not even in
    /// the flush loop); a failed mark resumes it, and it lands without having been dropped.
    #[tokio::test(start_paused = true)]
    async fn a_paused_stopped_sends_nothing_and_resumes_when_the_guard_drops() {
        let item = "supersede-pause-resume";
        let sink = PendingSink::new(STOPPED_FAILS, false);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "supersede-pause-resume-s"),
        );
        let_tasks_run().await;
        let delivery = session.stop(1).await;
        tokio::time::sleep(Duration::from_secs(5)).await;

        let guard = supersede(&[item]).await;
        let_tasks_run().await;
        let attempts = stopped_count(&sink);
        tokio::time::sleep(Duration::from_secs(600)).await;
        assert_eq!(stopped_count(&sink), attempts, "paused: nothing is sent");
        assert!(is_pending(item), "paused: the hold stays");

        drop(guard);
        sink.stopped_mode.store(STOPPED_OK, Ordering::Release);
        assert!(!delivery.settled().await, "resumed, not cancelled");
        assert_eq!(
            stopped_count(&sink),
            attempts + 1,
            "the Stopped landed once"
        );
        assert!(!registry_holds(item));
    }

    /// The actor hands a Stopped it finds paused to the flush queue instead of dropping it.
    #[tokio::test(start_paused = true)]
    async fn a_stopped_paused_before_its_first_attempt_lands_once_resumed() {
        let item = "supersede-pause-unsent";
        let sink = PendingSink::new(STOPPED_OK, false);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "supersede-pause-unsent-s"),
        );
        let_tasks_run().await;

        let stop = session.stop(1);
        let guard = supersede(&[item]).await;
        let delivery = stop.await;
        let_tasks_run().await;
        assert_eq!(stopped_count(&sink), 0, "paused: not sent");
        assert!(is_pending(item));

        drop(guard);
        assert!(!delivery.settled().await);
        assert_eq!(stopped_count(&sink), 1);
        assert!(!registry_holds(item));
    }

    /// A Start that repairs the final delivery is a request on the wire too: the superseding
    /// action waits for it, and nothing is sent after the commit.
    #[tokio::test(start_paused = true)]
    async fn supersede_waits_for_a_start_repair_and_nothing_follows_the_commit() {
        let item = "supersede-start-repair";
        let sink = Arc::new(StartGateSink::default());
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "supersede-start-repair-s"),
        );
        let_tasks_run().await;
        // The actor is stuck in its first Start, so stop() hands the pair to the flush queue.
        let delivery = session.stop(1).await;
        let_tasks_run().await;
        assert_eq!(
            sink.kinds(),
            ["Start", "Start"],
            "the repair is on the wire"
        );

        let mut waiting = Box::pin(supersede(&[item]));
        tokio::select! {
            _ = &mut waiting => panic!("supersede returned while the Start-repair is on the wire"),
            () = tokio::time::sleep(Duration::from_secs(600)) => {}
        }
        sink.gate.notify_one();
        let guard = waiting.await;
        let_tasks_run().await;
        assert_eq!(
            sink.kinds(),
            ["Start", "Start"],
            "paused: no Stopped follows the repair"
        );

        guard.commit();
        assert!(delivery.settled().await, "cancelled by the newer action");
        tokio::time::sleep(Duration::from_secs(120)).await;
        assert_eq!(sink.kinds(), ["Start", "Start"], "nothing after the commit");
        assert!(!registry_holds(item));
    }

    /// The actor's own Start-repair (the Start never acked) is counted on the wire as well.
    #[tokio::test(start_paused = true)]
    async fn supersede_waits_for_the_actors_start_repair() {
        let item = "supersede-actor-repair";
        let sink = Arc::new(StartGateSink {
            failing_starts: TRANSIENT_REPORT_MAX_ATTEMPTS as usize,
            ..Default::default()
        });
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "supersede-actor-repair-s"),
        );
        for _ in 0..8 {
            tokio::time::advance(Duration::from_secs(4)).await;
            tokio::task::yield_now().await;
        }
        assert!(!session.start_acked.load(Ordering::Relaxed));
        let delivery = session.stop(1).await;
        let_tasks_run().await;
        assert_eq!(
            sink.kinds().len(),
            TRANSIENT_REPORT_MAX_ATTEMPTS as usize + 1
        );

        let mut waiting = Box::pin(supersede(&[item]));
        tokio::select! {
            _ = &mut waiting => panic!("supersede returned while the Start-repair is on the wire"),
            () = tokio::time::sleep(Duration::from_secs(600)) => {}
        }
        sink.gate.notify_one();
        let guard = waiting.await;
        guard.commit();
        assert!(delivery.settled().await);
        tokio::time::sleep(Duration::from_secs(120)).await;
        assert!(
            !sink.kinds().contains(&"Stopped"),
            "nothing follows the commit"
        );
        assert!(!registry_holds(item));
    }

    #[test]
    fn begin_attempt_is_active_paused_or_cancelled() {
        let mut hold = PendingHold::new("begin-attempt-tri");
        let cancel = hold.begin_settling();
        assert!(matches!(hold.begin_attempt(), Begin::Go(_)));
        cancel.pause();
        assert!(matches!(hold.begin_attempt(), Begin::Paused));
        cancel.resume();
        assert!(matches!(hold.begin_attempt(), Begin::Go(_)));
        cancel.cancel();
        assert!(matches!(hold.begin_attempt(), Begin::Cancelled));
        drop(hold);
        assert!(!registry_holds("begin-attempt-tri"));
    }

    #[test]
    fn overlapping_pauses_resume_only_when_all_are_gone_and_a_cancel_is_final() {
        let cancel = Cancel::new();
        cancel.pause();
        cancel.pause();
        cancel.resume();
        assert_eq!(cancel.state(), CancelState::Paused);
        cancel.resume();
        assert_eq!(cancel.state(), CancelState::Active);
        cancel.pause();
        cancel.cancel();
        cancel.resume();
        assert_eq!(cancel.state(), CancelState::Cancelled);
    }

    /// docs/07 Watch state: an item is released with its last holder, whichever drops last.
    #[test]
    fn an_attempt_that_outlives_its_hold_releases_the_item() {
        let item = "release-attempt-last";
        let mut hold = PendingHold::new(item);
        hold.begin_settling();
        let Begin::Go(attempt) = hold.begin_attempt() else {
            panic!("an active delivery may attempt");
        };
        drop(hold);
        assert!(registry_holds(item), "the attempt still holds it");
        drop(attempt);
        assert!(!is_pending(item));
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn supersede_matches_the_item_in_any_spelling_and_ignores_others() {
        let sink = PendingSink::new(STOPPED_FAILS, false);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx("AABBCCDD-0000-0000-0000-0000000000D3", "supersede-spell-s"),
        );
        let other = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx("supersede-other", "supersede-other-s"),
        );
        let_tasks_run().await;
        let delivery = session.stop(1).await;
        let other_delivery = other.stop(1).await;
        let_tasks_run().await;

        supersede(&["aabbccdd0000000000000000000000d3"])
            .await
            .commit();
        delivery.settled().await;
        assert!(!registry_holds("aabbccdd0000000000000000000000d3"));
        assert!(
            is_pending("supersede-other"),
            "an unrelated item keeps retrying"
        );

        supersede(&["supersede-other"]).await.commit();
        other_delivery.settled().await;
        assert!(!registry_holds("supersede-other"));
    }

    #[tokio::test(start_paused = true)]
    async fn supersede_leaves_a_live_session_alone() {
        let item = "supersede-live";
        let sink = PendingSink::new(STOPPED_OK, false);
        let session =
            ReportingSession::start_with_sink(sink, pending_ctx(item, "supersede-live-s"));
        let_tasks_run().await;
        drop(supersede(&[item]).await);
        assert!(is_pending(item), "the live session still holds it");
        session.abandon();
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn supersede_returns_at_once_for_an_idle_item() {
        let start = tokio::time::Instant::now();
        drop(supersede(&["supersede-idle"]).await);
        assert_eq!(start.elapsed(), Duration::ZERO);
    }

    #[tokio::test(start_paused = true)]
    async fn a_replay_cancels_the_older_deliverys_retries() {
        let item = "supersede-replay";
        let sink = PendingSink::new(STOPPED_FAILS, false);
        let first =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "replay-first-s"));
        let_tasks_run().await;
        let delivery = first.stop(1).await;
        tokio::time::sleep(Duration::from_secs(5)).await;
        let attempts = stopped_count(&sink);
        assert!(attempts >= 2, "the Stopped is retrying");

        let second =
            ReportingSession::start_with_sink(sink.clone(), pending_ctx(item, "replay-second-s"));
        delivery.settled().await;
        tokio::time::sleep(Duration::from_secs(120)).await;
        assert_eq!(
            stopped_count(&sink),
            attempts,
            "the older Stopped stops retrying"
        );
        assert_eq!(lock_registry()[&canonical_id(item)].live, 1);
        second.abandon();
        assert!(!registry_holds(item));
    }

    #[tokio::test(start_paused = true)]
    async fn abandon_releases_the_item() {
        let item = "pending-abandon";
        let sink = PendingSink::new(STOPPED_OK, false);
        let session =
            ReportingSession::start_with_sink(sink, pending_ctx(item, "pending-abandon-s"));
        assert!(is_pending(item));
        session.abandon();
        assert!(!is_pending(item));
    }

    #[tokio::test(start_paused = true)]
    async fn two_sessions_on_one_item_keep_it_pending_until_both_release() {
        let item = "pending-refcount";
        let sink = PendingSink::new(STOPPED_OK, false);
        let first = ReportingSession::start_with_sink(
            sink.clone(),
            pending_ctx(item, "pending-refcount-a"),
        );
        let second =
            ReportingSession::start_with_sink(sink, pending_ctx(item, "pending-refcount-b"));
        first.abandon();
        assert!(is_pending(item), "the second session still holds it");
        second.abandon();
        assert!(!is_pending(item));
    }

    #[tokio::test(start_paused = true)]
    async fn flush_queue_dedups_a_second_enqueue_for_a_session_already_in_flight() {
        let sink = Arc::new(GatedSink::default());
        let ctx = ctx_with_session("dedup-session");
        let report = build_report(
            &ctx,
            PlaybackReportKind::Stopped,
            42,
            DEFAULT_VOLUME,
            None,
            None,
        );

        // `start_acked: true`: not exercising the never-acked-Start resend path here.
        flush_queue().enqueue(sink.clone(), ctx.clone(), true, report.clone(), None);
        tokio::task::yield_now().await;
        // Pins: a second enqueue for the same in-flight session is dropped, not a new task.
        flush_queue().enqueue(sink.clone(), ctx.clone(), true, report.clone(), None);
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        assert_eq!(
            sink.calls.lock().expect("lock").len(),
            1,
            "a duplicate enqueue for an in-flight session must not spawn a second task"
        );

        // Let the one in-flight attempt finish so the runtime shuts down cleanly.
        sink.gate.notify_waiters();
        tokio::task::yield_now().await;
    }

    #[tokio::test(start_paused = true)]
    async fn flush_queue_allows_concurrent_flushes_for_different_sessions() {
        let sink = Arc::new(GatedSink::default());
        let ctx_a = ctx_with_session("session-a");
        let ctx_b = ctx_with_session("session-b");
        let report_a = build_report(
            &ctx_a,
            PlaybackReportKind::Stopped,
            1,
            DEFAULT_VOLUME,
            None,
            None,
        );
        let report_b = build_report(
            &ctx_b,
            PlaybackReportKind::Stopped,
            2,
            DEFAULT_VOLUME,
            None,
            None,
        );

        flush_queue().enqueue(sink.clone(), ctx_a, true, report_a, None);
        flush_queue().enqueue(sink.clone(), ctx_b, true, report_b, None);
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        // Distinct sessions are not deduped; both should be in flight (blocked on the shared gate).
        assert_eq!(
            sink.calls.lock().expect("lock").len(),
            2,
            "different play_session_ids must be able to flush concurrently"
        );

        sink.gate.notify_waiters();
        tokio::task::yield_now().await;
    }

    #[tokio::test(start_paused = true)]
    async fn flush_queue_gives_up_after_the_retry_cap_instead_of_retrying_forever() {
        // Never succeeds -- the "server is permanently unreachable" case the cap exists for.
        let sink = FakeSink::new(u32::MAX);
        let ctx = ctx_with_session("cap-test-session");
        let report = build_report(
            &ctx,
            PlaybackReportKind::Stopped,
            7,
            DEFAULT_VOLUME,
            None,
            None,
        );
        flush_queue().enqueue(sink.clone(), ctx, true, report, None);

        // Advance well past FLUSH_MAX_DURATION (30 minutes).
        for _ in 0..40 {
            tokio::time::advance(Duration::from_secs(60)).await;
            tokio::task::yield_now().await;
            tokio::task::yield_now().await;
        }
        let calls_past_cap = sink.calls().len();
        assert!(
            calls_past_cap > 0,
            "expected at least one retry attempt before the cap"
        );

        // Pins: the call count does not keep growing once the task has given up.
        for _ in 0..15 {
            tokio::time::advance(Duration::from_secs(60)).await;
            tokio::task::yield_now().await;
            tokio::task::yield_now().await;
        }
        assert_eq!(
            sink.calls().len(),
            calls_past_cap,
            "flush must give up after FLUSH_MAX_DURATION instead of retrying forever"
        );
    }

    /// Pins: after an outage spanning the session (Start never acked, inline Stopped also fails),
    /// the flush notices `start_acked == false` and resends `Start` before the queued `Stopped`
    /// once the server recovers.
    #[tokio::test(start_paused = true)]
    async fn flush_resends_start_report_before_stopped_when_start_was_never_acked() {
        let sink = FakeSink::new(6);
        let session = ReportingSession::start_with_sink(
            sink.clone(),
            ctx_with_session("full-outage-session"),
        );

        // Advance in small steps, stopping at the 5th call, before the progress ticker would also
        // fire.
        for _ in 0..60 {
            tokio::task::yield_now().await;
            if sink.calls().len() >= 5 {
                break;
            }
            tokio::time::advance(Duration::from_secs(1)).await;
        }
        tokio::task::yield_now().await;
        assert_eq!(
            sink.calls().len(),
            5,
            "expected exactly the 5 bounded Start attempts from run() so far"
        );

        // The inline repair (`send_final_stopped`) is the 6th call, also fails; the ordered pair is
        // enqueued onto the FlushQueue.
        session.stop(555).await;

        // Let the FlushQueue's background retry run; every call from here on succeeds.
        for _ in 0..10 {
            tokio::task::yield_now().await;
            tokio::time::advance(Duration::from_secs(1)).await;
            tokio::task::yield_now().await;
        }

        let calls = sink.calls();
        let start_calls: Vec<_> = calls
            .iter()
            .filter(|r| matches!(r.kind, PlaybackReportKind::Start))
            .collect();
        let stopped_calls: Vec<_> = calls
            .iter()
            .filter(|r| matches!(r.kind, PlaybackReportKind::Stopped))
            .collect();

        assert_eq!(
            start_calls.len(),
            7,
            "expected 5 failed Start attempts from run(), 1 failed inline \
             Start repair, and 1 resent Start from the flush, got calls={calls:?}"
        );
        assert_eq!(
            stopped_calls.len(),
            1,
            "a bare Stopped must never be attempted while Start is unacked; \
             only the flush's ordered Stopped should appear, got calls={calls:?}"
        );
        assert_eq!(
            stopped_calls.last().expect("test assertion").position_ticks,
            555,
            "the delivered Stopped report must carry the real stop position"
        );
        assert!(
            matches!(
                calls.last().expect("test assertion").kind,
                PlaybackReportKind::Stopped
            ),
            "the resent Start must be sent (and succeed) before the final \
             Stopped report, got calls={calls:?}"
        );
    }

    // --- must_use + Drop warning -------------------------------

    /// Minimal `tracing::Subscriber` capturing event messages, so tests can assert a
    /// `warn!`/`error!` fired without a test-capture crate dependency.
    #[derive(Clone)]
    struct CapturingSubscriber {
        messages: Arc<Mutex<Vec<String>>>,
    }

    impl tracing::Subscriber for CapturingSubscriber {
        // A callsite first hit on a subscriber-less test thread caches `never`; `sometimes` keeps
        // this scoped subscriber asking per event.
        fn register_callsite(
            &self,
            _metadata: &'static tracing::Metadata<'static>,
        ) -> tracing::subscriber::Interest {
            tracing::subscriber::Interest::sometimes()
        }
        fn enabled(&self, _metadata: &tracing::Metadata<'_>) -> bool {
            true
        }
        fn new_span(&self, _span: &tracing::span::Attributes<'_>) -> tracing::span::Id {
            tracing::span::Id::from_u64(1)
        }
        fn record(&self, _span: &tracing::span::Id, _values: &tracing::span::Record<'_>) {}
        fn record_follows_from(&self, _span: &tracing::span::Id, _follows: &tracing::span::Id) {}
        fn event(&self, event: &tracing::Event<'_>) {
            struct MessageVisitor(String);
            impl tracing::field::Visit for MessageVisitor {
                fn record_debug(
                    &mut self,
                    field: &tracing::field::Field,
                    value: &dyn std::fmt::Debug,
                ) {
                    if field.name() == "message" {
                        self.0 = format!("{value:?}");
                    }
                }
            }
            let mut visitor = MessageVisitor(String::new());
            event.record(&mut visitor);
            self.messages.lock().expect("lock").push(visitor.0);
        }
        fn enter(&self, _span: &tracing::span::Id) {}
        fn exit(&self, _span: &tracing::span::Id) {}
    }

    #[tokio::test(start_paused = true)]
    // The only test that drops a session without stop(): a second one on another thread could
    // cache this warn callsite as disabled before the scoped subscriber registers.
    async fn dropping_without_stop_logs_a_warning_and_releases_the_item() {
        let messages = Arc::new(Mutex::new(Vec::new()));
        let subscriber = CapturingSubscriber {
            messages: messages.clone(),
        };
        let sink = FakeSink::new(0);

        tracing::subscriber::with_default(subscriber, || {
            tracing::callsite::rebuild_interest_cache();
            let session = ReportingSession::start_with_sink(
                sink.clone(),
                pending_ctx("pending-drop", "dropped-session"),
            );
            assert!(is_pending("pending-drop"));
            drop(session); // never called .stop()
            assert!(
                !is_pending("pending-drop"),
                "a dropped session releases its item"
            );
        });

        let logged = messages.lock().expect("lock");
        assert!(
            logged
                .iter()
                .any(|m| m.contains("dropped without calling stop")),
            "expected a warning that the session was dropped without stop(), got {logged:?}"
        );
    }

    #[tokio::test(start_paused = true)]
    async fn stop_suppresses_the_drop_warning() {
        let messages = Arc::new(Mutex::new(Vec::new()));
        let subscriber = CapturingSubscriber {
            messages: messages.clone(),
        };
        let sink = FakeSink::new(0);

        let session =
            ReportingSession::start_with_sink(sink.clone(), ctx_with_session("properly-stopped"));
        tokio::task::yield_now().await;

        // `with_default` only takes a sync closure, but Drop fires inside the awaited future;
        // `set_default` installs the subscriber until the guard drops.
        let _guard = tracing::subscriber::set_default(subscriber);
        session.stop(0).await; // properly stopped -- must NOT warn on drop
        drop(_guard);

        let logged = messages.lock().expect("lock");
        assert!(
            !logged
                .iter()
                .any(|m| m.contains("dropped without calling stop")),
            "stop() must suppress the drop-without-stop warning, got {logged:?}"
        );
    }
}
