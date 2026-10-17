//! Supervised WebSocket: owns reconnect/backoff, emits server events plus a `NeedsReconcile` per
//! connect, and publishes its state on a [`BusConnection`] watch. `EventBus::spawn` takes a
//! `JellyfinClient` and returns that event receiver, the watch and its handle; [`supervise`] is
//! generic over a private [`WsConnect`] trait so tests can drive it with a scripted fake connector.

use std::future::Future;
use std::pin::Pin;
use std::time::Duration;

use jellyfin_api::{ApiError, JellyfinClient, ServerEvent};
use tokio::sync::{broadcast, mpsc, watch};

use crate::backoff::Backoff;
use crate::BusEvent;

const RECONNECT_BASE: Duration = Duration::from_secs(1);
const RECONNECT_CAP: Duration = Duration::from_secs(60);
const BROADCAST_CAPACITY: usize = 256;

/// A connection "proves useful" (earning a backoff reset) once held this long or a real server
/// event arrives; otherwise a server dropping immediately would pin backoff at ~1s forever.
const USEFUL_CONNECTION_DURATION: Duration = Duration::from_secs(30);

type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub(crate) trait WsConnect: Send + Sync + 'static {
    fn connect(&self) -> BoxFuture<'_, Result<mpsc::Receiver<ServerEvent>, ApiError>>;
}

impl WsConnect for JellyfinClient {
    fn connect(&self) -> BoxFuture<'_, Result<mpsc::Receiver<ServerEvent>, ApiError>> {
        Box::pin(self.connect_ws())
    }
}

/// The socket's state, `Unauthorized` terminal; `supervise` is its only writer, so readers derive
/// status from `borrow()`, never from status events a lagging broadcast consumer can drop.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BusConnection {
    Disconnected,
    Connected,
    Unauthorized {
        owner: Option<jellyfin_api::TokenOwner>,
    },
}

pub struct EventBus {
    _priv: (),
}

impl EventBus {
    /// Spawns the supervisor. The returned [`EventBusHandle`] must be kept
    /// alive for as long as the bus should run; dropping it (or calling
    /// [`EventBusHandle::shutdown`]) terminates the loop and closes the socket.
    pub fn spawn(
        client: JellyfinClient,
    ) -> (
        broadcast::Receiver<BusEvent>,
        watch::Receiver<BusConnection>,
        EventBusHandle,
    ) {
        let (tx, rx) = broadcast::channel(BROADCAST_CAPACITY);
        let (connection_tx, connection_rx) = watch::channel(BusConnection::Disconnected);
        let task = tokio::spawn(supervise(client, tx.clone(), connection_tx));
        (rx, connection_rx, EventBusHandle { task: Some(task) })
    }
}

/// Handle controlling the lifetime of a spawned [`EventBus`] supervisor;
/// without it a supervisor task leaks and retries forever.
pub struct EventBusHandle {
    task: Option<tokio::task::JoinHandle<()>>,
}

impl EventBusHandle {
    /// Stops the supervision loop and waits for it to fully exit. Aborting
    /// the task drops any in-flight connect future or live connection
    /// receiver, which `ws.rs`'s `tx.closed()` race turns into a prompt
    /// WebSocket `Close` frame rather than a dangling socket.
    ///
    /// Prefer this over dropping the handle when the caller can await;
    /// [`Drop`] performs the same abort as a best-effort fallback.
    pub async fn shutdown(mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
            let _ = task.await;
        }
    }
}

impl Drop for EventBusHandle {
    fn drop(&mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

/// The supervision loop: connect, forward events until the connection drops
/// or fails, publish the connection state on the watch around both, then reconnect
/// with jittered capped backoff. Runs until [`EventBusHandle::shutdown`]/
/// [`Drop`] aborts the owning task.
async fn supervise<C: WsConnect>(
    connector: C,
    tx: broadcast::Sender<BusEvent>,
    connection: watch::Sender<BusConnection>,
) {
    let mut backoff = Backoff::new(RECONNECT_BASE, RECONNECT_CAP);
    loop {
        match connector.connect().await {
            Ok(mut rx) => {
                // `send_replace`, not `send`: the state is stored even while no receiver is alive.
                connection.send_replace(BusConnection::Connected);
                // Every successful (re)connect may have missed events while down; the caller must
                // reconcile.
                let _ = tx.send(BusEvent::NeedsReconcile);

                // Backoff resets only once this connection proves useful (see
                // USEFUL_CONNECTION_DURATION); until then a dropped connection
                // continues escalating rather than restarting from the base delay.
                let proved_useful_at = tokio::time::sleep(USEFUL_CONNECTION_DURATION);
                tokio::pin!(proved_useful_at);
                let mut proved_useful = false;

                loop {
                    tokio::select! {
                        biased;
                        () = &mut proved_useful_at, if !proved_useful => {
                            proved_useful = true;
                            backoff.reset();
                        }
                        event = rx.recv() => {
                            match event {
                                Some(event) => {
                                    if !proved_useful {
                                        proved_useful = true;
                                        backoff.reset();
                                    }
                                    let _ = tx.send(BusEvent::Server(event));
                                }
                                None => break,
                            }
                        }
                    }
                }
                connection.send_replace(BusConnection::Disconnected);
            }
            // docs/18 §2.1: a rejected token needs reauthorization, so retrying it only repeats the 401.
            Err(ApiError::Unauthorized { owner }) => {
                tracing::warn!("websocket token rejected, stopping reconnects");
                connection.send_replace(BusConnection::Unauthorized { owner });
                return;
            }
            Err(err) => {
                tracing::warn!(?err, "websocket connect failed, will retry with backoff");
                connection.send_replace(BusConnection::Disconnected);
            }
        }
        tokio::time::sleep(backoff.next_delay()).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::sync::{Arc, Mutex};

    enum Outcome {
        Ok(Vec<ServerEvent>),
        Err,
    }

    /// Plays `outcomes` in order, then fails every later connect; counts every attempt.
    struct FakeConnector {
        attempts: Arc<std::sync::atomic::AtomicUsize>,
        outcomes: Mutex<VecDeque<Outcome>>,
    }

    impl WsConnect for FakeConnector {
        fn connect(&self) -> BoxFuture<'_, Result<mpsc::Receiver<ServerEvent>, ApiError>> {
            self.attempts
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Box::pin(async move {
                let outcome = self.outcomes.lock().expect("lock").pop_front();
                match outcome {
                    Some(Outcome::Ok(events)) => {
                        let (tx, rx) = mpsc::channel(16);
                        tokio::spawn(async move {
                            for event in events {
                                if tx.send(event).await.is_err() {
                                    break;
                                }
                            }
                            // tx dropped here: rx.recv() -> None, simulating
                            // a dropped connection.
                        });
                        Ok(rx)
                    }
                    Some(Outcome::Err) | None => Err(ApiError::Transport("simulated".into())),
                }
            })
        }
    }

    /// Connects successfully but never proves useful (no events, sender
    /// dropped immediately); records the connect() timestamps so tests can
    /// inspect reconnect cadence.
    struct FlappingState {
        call_times: Mutex<Vec<tokio::time::Instant>>,
    }

    struct FlappingConnector(Arc<FlappingState>);

    impl WsConnect for FlappingConnector {
        fn connect(&self) -> BoxFuture<'_, Result<mpsc::Receiver<ServerEvent>, ApiError>> {
            let state = self.0.clone();
            Box::pin(async move {
                state
                    .call_times
                    .lock()
                    .expect("lock")
                    .push(tokio::time::Instant::now());
                let (_tx, rx) = mpsc::channel::<ServerEvent>(1);
                // `_tx` drops here -> `rx.recv()` immediately yields `None`.
                Ok(rx)
            })
        }
    }

    /// Connects and stays open until the test drops the stashed sender.
    struct HangingState {
        sender: Mutex<Option<mpsc::Sender<ServerEvent>>>,
    }

    struct HangingConnector(Arc<HangingState>);

    impl WsConnect for HangingConnector {
        fn connect(&self) -> BoxFuture<'_, Result<mpsc::Receiver<ServerEvent>, ApiError>> {
            let state = self.0.clone();
            Box::pin(async move {
                let (tx, rx) = mpsc::channel(16);
                *state.sender.lock().expect("lock") = Some(tx);
                Ok(rx)
            })
        }
    }

    /// Each row of the connection table, driven to a resting point: the watch holds the row's
    /// state, and a publish (not just the initial value) happened.
    #[tokio::test(start_paused = true)]
    async fn supervise_publishes_the_connection_state_on_every_transition() {
        let mut failures = Vec::new();

        // Opened -> Connected, then a server event keeps it Connected.
        let state = Arc::new(HangingState {
            sender: Mutex::new(None),
        });
        let (tx, mut bus) = broadcast::channel(256);
        let (connection_tx, mut connection) = watch::channel(BusConnection::Disconnected);
        let task = tokio::spawn(supervise(
            HangingConnector(state.clone()),
            tx,
            connection_tx,
        ));
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        let row = (*connection.borrow_and_update()).clone();
        if row != BusConnection::Connected {
            failures.push(format!("Opened: expected Connected, got {row:?}"));
        }
        let sender = state
            .sender
            .lock()
            .expect("lock")
            .take()
            .expect("connected");
        sender
            .send(ServerEvent::ForceKeepAlive)
            .await
            .expect("send");
        while !matches!(bus.recv().await, Ok(BusEvent::Server(_))) {}
        if connection.has_changed().expect("supervisor alive") {
            failures.push(format!(
                "ServerEvent: expected no publish, got {:?}",
                *connection.borrow()
            ));
        }

        // Closed -> Disconnected; supervise rests in its backoff sleep.
        drop(sender);
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        let changed = connection.has_changed().expect("supervisor alive");
        let row = (*connection.borrow_and_update()).clone();
        if !changed || row != BusConnection::Disconnected {
            failures.push(format!(
                "Closed: expected a Disconnected publish, got {row:?} (changed {changed})"
            ));
        }
        task.abort();

        // ConnectFailed -> Disconnected stays, but is still published.
        let connector = FakeConnector {
            attempts: Arc::default(),
            outcomes: Mutex::new(VecDeque::new()),
        };
        let (tx, _bus) = broadcast::channel(256);
        let (connection_tx, mut connection) = watch::channel(BusConnection::Disconnected);
        let task = tokio::spawn(supervise(connector, tx, connection_tx));
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        let changed = connection.has_changed().expect("supervisor alive");
        let row = (*connection.borrow_and_update()).clone();
        if !changed || row != BusConnection::Disconnected {
            failures.push(format!(
                "ConnectFailed: expected a Disconnected publish, got {row:?} (changed {changed})"
            ));
        }
        task.abort();

        assert!(failures.is_empty(), "{failures:#?}");
    }

    /// Rejects every connect with a 401 naming `owner`, counting the attempts.
    struct RejectedTokenConnector {
        owner: Option<jellyfin_api::TokenOwner>,
        attempts: Arc<std::sync::atomic::AtomicUsize>,
    }

    impl WsConnect for RejectedTokenConnector {
        fn connect(&self) -> BoxFuture<'_, Result<mpsc::Receiver<ServerEvent>, ApiError>> {
            self.attempts
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            let owner = self.owner.clone();
            Box::pin(async move { Err(ApiError::Unauthorized { owner }) })
        }
    }

    /// docs/18 §2.1: a rejected socket token is reauthorization, not an outage -- one attempt, the
    /// watch resting on `Unauthorized` naming the account, nothing on the bus, and no reconnect.
    #[tokio::test(start_paused = true)]
    async fn a_rejected_socket_token_is_reported_once_and_never_retried() {
        let owners = [
            Some(jellyfin_api::TokenOwner {
                server_url: "http://server.test".to_string(),
                user_id: "user-1".to_string(),
            }),
            None,
        ];
        for owner in owners {
            let attempts = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let connector = RejectedTokenConnector {
                owner: owner.clone(),
                attempts: attempts.clone(),
            };
            let (tx, mut rx) = broadcast::channel(256);
            let (connection_tx, mut connection) = watch::channel(BusConnection::Disconnected);
            tokio::spawn(supervise(connector, tx, connection_tx));

            let mut events = Vec::new();
            for _ in 0..300 {
                tokio::task::yield_now().await;
                tokio::time::advance(Duration::from_secs(1)).await;
                while let Ok(ev) = rx.try_recv() {
                    events.push(ev);
                }
            }
            tokio::task::yield_now().await;
            while let Ok(ev) = rx.try_recv() {
                events.push(ev);
            }

            assert_eq!(
                attempts.load(std::sync::atomic::Ordering::SeqCst),
                1,
                "a 401 must not be reconnected over five paused minutes ({owner:?}); events {events:?}"
            );
            assert!(
                events.is_empty(),
                "a rejected token sends nothing on the bus; events {events:?}"
            );
            assert_eq!(
                *connection.borrow_and_update(),
                BusConnection::Unauthorized {
                    owner: owner.clone()
                },
                "the watch rests on Unauthorized naming the account"
            );
            assert!(
                connection.changed().await.is_err(),
                "Unauthorized is terminal: supervise returned and closed the watch"
            );
        }
    }

    fn is_server_force_keep_alive(ev: &BusEvent) -> bool {
        matches!(ev, BusEvent::Server(ServerEvent::ForceKeepAlive))
    }

    /// Drains what the supervision loop has produced so far, jumping the
    /// paused clock 65s per round (past the 60s cap) to unstick backoff
    /// sleeps. Only safe for tests asserting event order/type, not backoff
    /// timing -- see
    /// `needs_reconcile_fires_on_every_successful_reconnect_and_backoff_still_escalates`
    /// for a timing-sensitive test that steps the clock finely instead.
    async fn drain(rx: &mut broadcast::Receiver<BusEvent>, rounds: u32) -> Vec<BusEvent> {
        let mut events = Vec::new();
        for _ in 0..rounds {
            tokio::task::yield_now().await;
            while let Ok(ev) = rx.try_recv() {
                events.push(ev);
            }
            tokio::time::advance(Duration::from_secs(65)).await;
        }
        tokio::task::yield_now().await;
        while let Ok(ev) = rx.try_recv() {
            events.push(ev);
        }
        events
    }

    /// Each ConnectOk sends exactly one `NeedsReconcile` before its server events, and
    /// nothing else reaches the bus; the watch rests on Disconnected once connects keep failing.
    #[tokio::test(start_paused = true)]
    async fn reconnect_sequence_sends_one_needs_reconcile_per_connect_then_its_server_events() {
        let connector = FakeConnector {
            attempts: Arc::default(),
            outcomes: Mutex::new(VecDeque::from([
                Outcome::Err,
                Outcome::Ok(vec![ServerEvent::ForceKeepAlive]),
                Outcome::Err,
                Outcome::Ok(vec![]),
            ])),
        };
        let (tx, mut rx) = broadcast::channel(256);
        let (connection_tx, mut connection) = watch::channel(BusConnection::Disconnected);
        tokio::spawn(supervise(connector, tx, connection_tx));

        let events = drain(&mut rx, 12).await;

        assert_eq!(events.len(), 3, "{events:?}");
        assert!(
            matches!(events[0], BusEvent::NeedsReconcile),
            "{:?}",
            events[0]
        );
        assert!(is_server_force_keep_alive(&events[1]), "{:?}", events[1]);
        assert!(
            matches!(events[2], BusEvent::NeedsReconcile),
            "{:?}",
            events[2]
        );
        assert!(connection.has_changed().expect("supervisor alive"));
        assert_eq!(*connection.borrow_and_update(), BusConnection::Disconnected);
    }

    /// Pins: against a flapping connector (connects, then drops with zero
    /// events), NeedsReconcile fires on every reconnect and the backoff gap
    /// keeps escalating rather than resetting to ~1s each time.
    #[tokio::test(start_paused = true)]
    async fn needs_reconcile_fires_on_every_successful_reconnect_and_backoff_still_escalates() {
        let state = Arc::new(FlappingState {
            call_times: Mutex::new(Vec::new()),
        });
        let (tx, mut rx) = broadcast::channel(256);
        tokio::spawn(supervise(
            FlappingConnector(state.clone()),
            tx,
            watch::channel(BusConnection::Disconnected).0,
        ));

        // Fine-grained stepping (not `drain`'s 65s jump) so the recorded
        // `Instant`s reflect the true reconnect cadence.
        for _ in 0..200 {
            tokio::time::advance(Duration::from_millis(250)).await;
            tokio::task::yield_now().await;
        }

        let mut reconcile_count = 0;
        while let Ok(ev) = rx.try_recv() {
            if matches!(ev, BusEvent::NeedsReconcile) {
                reconcile_count += 1;
            }
        }
        assert!(
            reconcile_count >= 3,
            "expected NeedsReconcile on every reconnect (>=3 in this window), got {reconcile_count}"
        );

        let calls = state.call_times.lock().expect("lock").clone();
        assert!(
            calls.len() >= 4,
            "expected several reconnect attempts, got {}",
            calls.len()
        );
        let gaps: Vec<Duration> = calls.windows(2).map(|w| w[1] - w[0]).collect();
        for i in 1..gaps.len() {
            assert!(
                gaps[i] > gaps[i - 1],
                "backoff must escalate, not reset, across repeated flapping \
                 reconnects: gaps={gaps:?}"
            );
        }
    }

    /// Pins: backoff not only escalates monotonically across repeated
    /// flapping reconnects but reaches well past its 1s base, approaching
    /// the 60s cap.
    #[tokio::test(start_paused = true)]
    async fn backoff_escalates_on_repeated_flapping_reconnects() {
        let state = Arc::new(FlappingState {
            call_times: Mutex::new(Vec::new()),
        });
        let (tx, _rx) = broadcast::channel(256);
        tokio::spawn(supervise(
            FlappingConnector(state.clone()),
            tx,
            watch::channel(BusConnection::Disconnected).0,
        ));

        for _ in 0..400 {
            tokio::time::advance(Duration::from_millis(250)).await;
            tokio::task::yield_now().await;
        }

        let calls = state.call_times.lock().expect("lock").clone();
        assert!(
            calls.len() >= 6,
            "expected multiple reconnect attempts, got {}",
            calls.len()
        );
        let gaps: Vec<Duration> = calls.windows(2).map(|w| w[1] - w[0]).collect();
        for i in 1..gaps.len() {
            assert!(
                gaps[i] > gaps[i - 1],
                "backoff must monotonically escalate on flapping reconnects: gaps={gaps:?}"
            );
        }
        assert!(
            gaps.last().expect("at least one gap").as_secs_f64() > 5.0,
            "expected backoff to escalate well past its 1s base by the last \
             recorded gap, gaps={gaps:?}"
        );
    }

    #[tokio::test(start_paused = true)]
    async fn keeps_retrying_forever_after_repeated_failures() {
        let attempts = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let connector = FakeConnector {
            attempts: attempts.clone(),
            outcomes: Mutex::new(VecDeque::new()),
        };
        let (tx, mut rx) = broadcast::channel(256);
        let (connection_tx, mut connection) = watch::channel(BusConnection::Disconnected);
        tokio::spawn(supervise(connector, tx, connection_tx));

        let events = drain(&mut rx, 10).await;
        assert!(
            attempts.load(std::sync::atomic::Ordering::SeqCst) >= 5,
            "a failed connect is retried"
        );
        assert!(
            events.is_empty(),
            "a failed connect sends nothing on the bus; events {events:?}"
        );
        assert!(connection.has_changed().expect("supervisor still alive"));
        assert_eq!(*connection.borrow_and_update(), BusConnection::Disconnected);
    }

    /// Pins: `shutdown()` aborts the loop and the task actually finishes,
    /// proven by the `broadcast::Sender` it owned being dropped (closing
    /// every receiver).
    #[tokio::test(start_paused = true)]
    async fn shutdown_terminates_the_supervision_loop_and_closes_the_broadcast_channel() {
        let connector = FakeConnector {
            attempts: Arc::default(),
            outcomes: Mutex::new(VecDeque::new()), // always Err -> retries forever until stopped
        };
        let (tx, mut rx) = broadcast::channel(256);
        let task = tokio::spawn(supervise(
            connector,
            tx,
            watch::channel(BusConnection::Disconnected).0,
        ));
        let handle = EventBusHandle { task: Some(task) };

        tokio::task::yield_now().await;
        while rx.try_recv().is_ok() {}

        handle.shutdown().await;

        match rx.recv().await {
            Err(broadcast::error::RecvError::Closed) => {}
            other => {
                panic!("expected the broadcast channel to close after shutdown, got {other:?}")
            }
        }
    }

    /// Pins: `shutdown()` also closes any live connection, not just stops
    /// reconnecting -- dropping supervise's `mpsc::Receiver` is what tells a
    /// live socket (`ws.rs`'s `tx.closed()` race) to send a `Close` frame.
    #[tokio::test(start_paused = true)]
    async fn shutdown_drops_the_live_connection_promptly() {
        let state = Arc::new(HangingState {
            sender: Mutex::new(None),
        });
        let (tx, _rx) = broadcast::channel(256);
        let task = tokio::spawn(supervise(
            HangingConnector(state.clone()),
            tx,
            watch::channel(BusConnection::Disconnected).0,
        ));
        let handle = EventBusHandle { task: Some(task) };

        tokio::task::yield_now().await;
        tokio::task::yield_now().await;
        let sender = state
            .sender
            .lock()
            .expect("lock")
            .clone()
            .expect("connector should have connected by now");
        assert!(!sender.is_closed(), "sanity: not yet closed pre-shutdown");

        handle.shutdown().await;

        assert!(
            sender.is_closed(),
            "expected the live connection's receiver to be dropped (closing \
             any live WS) once shutdown() returns"
        );
    }

    /// [`Drop`] is the fallback path for callers that can't await
    /// `shutdown()`; it must still terminate the loop (best-effort, via
    /// abort) rather than leaking it.
    #[tokio::test(start_paused = true)]
    async fn dropping_the_handle_also_terminates_the_supervision_loop() {
        let connector = FakeConnector {
            attempts: Arc::default(),
            outcomes: Mutex::new(VecDeque::new()),
        };
        let (tx, mut rx) = broadcast::channel(256);
        let task = tokio::spawn(supervise(
            connector,
            tx,
            watch::channel(BusConnection::Disconnected).0,
        ));
        let handle = EventBusHandle { task: Some(task) };

        tokio::task::yield_now().await;
        while rx.try_recv().is_ok() {}

        drop(handle);
        // Give the abort a scheduler tick to take effect.
        tokio::task::yield_now().await;
        tokio::task::yield_now().await;

        match rx.recv().await {
            Err(broadcast::error::RecvError::Closed) => {}
            other => panic!("expected the broadcast channel to close after drop, got {other:?}"),
        }
    }
}
