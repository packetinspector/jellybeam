//! The render thread. It alone owns the substation library, renderer and tracks; callers send
//! commands. One item at a time: [`Engine::begin_item`] frees the last item's tracks, renderer and fonts.

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::budget::{FrameTimes, Ladder, Quality};
use crate::colour::{self, Mangle, VideoColour};
use crate::compositor::{Canvas, Coverage, Rect};
use crate::dialogue;
use crate::events::{
    pressure_prune_deadline, shows_within, EventBudget, MAX_HEADER_BYTES, PRUNE_DELAY_MS,
};
use crate::fonts::{Admission, FontStore};
use crate::limits::MAX_FONT_BYTES;
use crate::limits::{refusal_backoff_ms, security};
use crate::script::{FontCandidates, Script, Tally};
use crate::{lock, note};
use substation::api::{Hinting, ShapingLevel};
use substation::{script, FontFallback, FrameAdmission, Library, Renderer, SecurityConfig, Track};

/// Where finished frames go: an Android window in the app, a buffer in tests.
pub trait FrameSink: Send {
    /// Resizes the output buffer; `false` when the sink can't take that size.
    fn configure(&mut self, width: i32, height: i32) -> bool;
    /// Shows `canvas`; only `dirty` differs from the previous present.
    fn present(&mut self, canvas: &Canvas, dirty: Rect) -> bool;
}

#[derive(Debug, Clone)]
pub struct EngineConfig {
    /// Embedded fonts kept per item; extra fonts are skipped, not loaded.
    pub font_budget_bytes: usize,
    pub bitmap_cache_mb: i32,
    pub glyph_cache_max: i32,
    /// p90 frame cost above which the quality ladder steps down.
    pub frame_budget_ms: f32,
    /// Render-buffer pixel cap before quality scaling; 1080p is the UI's own resolution.
    pub max_render_pixels: i64,
    /// substation work units one frame may use before admission refuses it (limits.rs).
    pub frame_work: u64,
    pub fonts: FontCandidates,
}

impl EngineConfig {
    /// docs/09: (largest single font, per-item total) in bytes; the player fetches nothing past
    /// these, and the font store and substation's limits use the same numbers.
    pub fn font_limits(&self) -> (usize, usize) {
        (MAX_FONT_BYTES, self.font_budget_bytes)
    }
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            font_budget_bytes: 48 << 20,
            bitmap_cache_mb: 32,
            glyph_cache_max: 4000,
            frame_budget_ms: 20.0,
            max_render_pixels: 1920 * 1080,
            // Units track no clock: a 7038-event stress script needs 1.7G units in 11 ms, and
            // substation's pathological test set peaks at 7.4G in 29 ms (desktop), which this refuses.
            frame_work: 1 << 32,
            fonts: FontCandidates::default(),
        }
    }
}

/// A snapshot for the stats sheet and diagnostics.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Stats {
    /// The substation library exists: a styled track was seen this process.
    pub renderer_loaded: bool,
    pub track_selected: bool,
    pub frames_drawn: u64,
    pub last_frame_ms: f32,
    pub window_p90_ms: f32,
    pub quality: Quality,
    pub render_width: i32,
    pub render_height: i32,
    pub font_bytes: usize,
    pub fonts_skipped: u32,
    /// Events not loaded because they were oversized or over the item's live-event budget.
    pub events_skipped: u32,
    pub default_font_script: Option<Script>,
    /// Frames substation's admission refused (work or memory budget) this item.
    pub frames_refused: u32,
    /// Header or event input substation refused against its input limits this item.
    pub input_refusals: u64,
    /// Bytes the renderer's admission ledger holds now, and its peak this item.
    pub render_memory_bytes: usize,
    pub render_peak_bytes: usize,
    /// The most work units one frame used this item.
    pub max_frame_work: u64,
}

enum Cmd {
    BeginItem,
    AddFont {
        name: String,
        data: Vec<u8>,
    },
    AddTrack {
        key: String,
        header: Vec<u8>,
    },
    /// (media time µs, payload) in stream order.
    AddSamples {
        key: String,
        samples: Vec<(i64, Vec<u8>)>,
    },
    AddScript {
        key: String,
        data: Vec<u8>,
        item: u64,
        done: SyncSender<bool>,
    },
    Select(Option<String>),
    /// Deselects this track only if it is still the selected one.
    Unselect(String),
    Position {
        pos_us: i64,
        playing: bool,
    },
    VideoSize {
        width: i32,
        height: i32,
    },
    VideoColour(VideoColour),
    /// The output window changed; [`Output`] already holds the new one.
    OutputChanged,
    LineLift(f64),
    HoldFrames(bool),
    #[cfg(test)]
    Stall {
        entered: SyncSender<()>,
        release: Receiver<()>,
    },
    Shutdown,
}

/// The output window, swapped by the UI thread without waiting for a frame: the render thread
/// holds this lock only to configure and copy into the window, never while rendering.
#[derive(Default)]
struct Output {
    sink: Option<Box<dyn FrameSink>>,
    /// The window's size in pixels, before the quality ladder.
    size: (i32, i32),
    /// The buffer size `sink` was configured to; `None` for a new window, which gets a full repaint.
    configured: Option<(i32, i32)>,
}

pub struct Engine {
    tx: Sender<Cmd>,
    stats: Arc<Mutex<Stats>>,
    output: Arc<Mutex<Output>>,
    thread: Mutex<Option<JoinHandle<()>>>,
    /// Items begun so far; the render thread counts the same commands.
    item: AtomicU64,
}

impl Engine {
    pub fn spawn(cfg: EngineConfig) -> std::io::Result<Self> {
        let (tx, rx) = mpsc::channel();
        let stats = Arc::new(Mutex::new(Stats::default()));
        let output = Arc::new(Mutex::new(Output::default()));
        let worker = Worker::new(cfg, Arc::clone(&stats), Arc::clone(&output));
        let thread = thread::Builder::new()
            .name("ass-render".into())
            .spawn(move || worker.run(rx))?;
        Ok(Self {
            tx,
            stats,
            output,
            thread: Mutex::new(Some(thread)),
            item: AtomicU64::new(0),
        })
    }

    fn send(&self, cmd: Cmd) {
        // A dead render thread means no subtitles, never a crash on the caller's thread.
        let _ = self.tx.send(cmd);
    }

    pub fn begin_item(&self) {
        self.item.fetch_add(1, Ordering::SeqCst);
        self.send(Cmd::BeginItem);
    }

    /// The current item, for an [`Self::add_script`] fetched on its behalf on another thread.
    pub fn item(&self) -> u64 {
        self.item.load(Ordering::SeqCst)
    }

    pub fn add_font(&self, name: String, data: Vec<u8>) {
        self.send(Cmd::AddFont { name, data });
    }

    pub fn add_track(&self, key: String, header: Vec<u8>) {
        self.send(Cmd::AddTrack { key, header });
    }

    /// Media3 SSA samples of track `key` in stream order, (media time µs, payload), as one command.
    pub fn add_samples(&self, key: String, samples: Vec<(i64, Vec<u8>)>) {
        if !samples.is_empty() {
            self.send(Cmd::AddSamples { key, samples });
        }
    }

    #[cfg(test)]
    pub(crate) fn add_sample(&self, key: String, time_us: i64, data: Vec<u8>) {
        self.add_samples(key, vec![(time_us, data)]);
    }

    /// docs/18 §3.2: a whole script (a sidecar file) as track `key` of `item`, replacing that item's
    /// previous script; blocks until parsed. False once `item` has ended, for a script with no
    /// events, or with the render thread gone.
    pub fn add_script(&self, key: String, data: Vec<u8>, item: u64) -> bool {
        let (done, result) = mpsc::sync_channel(1);
        self.send(Cmd::AddScript {
            key,
            data,
            item,
            done,
        });
        result.recv().unwrap_or(false)
    }

    pub fn select(&self, key: Option<String>) {
        self.send(Cmd::Select(key));
    }

    /// Deselects `key` unless another track was selected since, whichever thread asked first.
    pub fn unselect(&self, key: String) {
        self.send(Cmd::Unselect(key));
    }

    pub fn set_position(&self, pos_us: i64, playing: bool) {
        self.send(Cmd::Position { pos_us, playing });
    }

    pub fn set_video_size(&self, width: i32, height: i32) {
        self.send(Cmd::VideoSize { width, height });
    }

    /// The video's colour space, which the selected track's `YCbCr Matrix` converts its colours for.
    pub fn set_video_colour(&self, colour: VideoColour) {
        self.send(Cmd::VideoColour(colour));
    }

    /// Percent (0-100) the bottom subtitles move up, for the OSD; positioned signs stay put.
    pub fn set_line_lift(&self, percent: f64) {
        self.send(Cmd::LineLift(percent.clamp(0.0, 100.0)));
    }

    /// While held, the overlay shows nothing: a styled track waits for its fonts rather than draw
    /// in stand-ins (docs/09).
    pub fn hold_frames(&self, held: bool) {
        self.send(Cmd::HoldFrames(held));
    }

    /// Swaps the output window. On return the old one is released and never drawn to again, as
    /// `SurfaceHolder.Callback.surfaceDestroyed` requires; this waits at most for one copy into
    /// the window, never for a frame, so a slow frame can't stall the UI thread.
    pub fn set_sink(&self, sink: Option<(Box<dyn FrameSink>, i32, i32)>) {
        let old = {
            let mut out = lock(&self.output);
            let (sink, size) = sink.map_or((None, (0, 0)), |(s, w, h)| (Some(s), (w, h)));
            out.size = size;
            out.configured = None;
            std::mem::replace(&mut out.sink, sink)
        };
        drop(old);
        self.send(Cmd::OutputChanged);
    }

    /// Parks the render thread until the returned sender sends or drops, as a slow frame would.
    #[cfg(test)]
    pub(crate) fn stall(&self) -> SyncSender<()> {
        let (entered, on_entered) = mpsc::sync_channel(1);
        let (release, on_release) = mpsc::sync_channel(1);
        self.send(Cmd::Stall {
            entered,
            release: on_release,
        });
        assert!(on_entered.recv().is_ok(), "render thread is gone");
        release
    }

    pub fn stats(&self) -> Stats {
        lock(&self.stats).clone()
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.send(Cmd::Shutdown);
        let thread = lock(&self.thread).take();
        if let Some(t) = thread {
            let _ = t.join();
        }
    }
}

/// 30 updates a second is smoother than any subtitle animation needs at TV distances.
const FRAME_INTERVAL: Duration = Duration::from_millis(33);
const WINDOW: Duration = Duration::from_secs(2);
/// Re-check the dialogue's script this often while samples arrive.
const RETALLY_EVERY: u32 = 64;

struct Worker {
    cfg: EngineConfig,
    security: SecurityConfig,
    stats: Arc<Mutex<Stats>>,
    lib: Option<Library>,
    renderer: Option<Renderer>,
    tracks: HashMap<String, Track>,
    tallies: HashMap<String, Tally>,
    selected: Option<String>,
    /// Items begun, counted as [`Engine::item`] counts them.
    item: u64,
    /// The item's whole-script track, if any; at most one is held.
    script: Option<String>,
    fonts: FontStore,
    events: EventBudget,
    default_script: Option<Script>,
    samples_since_tally: u32,
    output: Arc<Mutex<Output>>,
    video: (i32, i32),
    video_colour: VideoColour,
    /// The selected track's colour conversion for this video (colour.rs); `None` keeps colours.
    mangle: Option<Mangle>,
    canvas: Canvas,
    ladder: Ladder,
    times: FrameTimes,
    window_start: Instant,
    pos_us: i64,
    pos_at: Instant,
    playing: bool,
    last_render: Option<(Instant, i64)>,
    force: bool,
    /// A failed present left the screen out of step with the canvas; repaint all of it next time.
    repaint_all: bool,
    line_lift: f64,
    /// Fonts are still arriving, so nothing is drawn.
    held: bool,
    /// Refused frames in a row, and the media span `[refused, retry)` no frame is tried in; a seek
    /// out of it, either way, tries at once.
    refusals_in_a_row: u32,
    backoff: Option<(i64, i64)>,
    frames_refused: u32,
    max_frame_work: u64,
}

/// How long the render loop sleeps: an idle second unless a track is showing and playing; held
/// frames draw nothing, and `HoldFrames(false)` arrives as a command that wakes the loop.
fn wait_for(
    showing_and_playing: bool,
    held: bool,
    since_render: Option<Duration>,
    interval: Duration,
) -> Duration {
    const IDLE: Duration = Duration::from_secs(1);
    if !showing_and_playing || held {
        return IDLE;
    }
    since_render.map_or(Duration::ZERO, |e| interval.saturating_sub(e))
}

impl Worker {
    fn new(cfg: EngineConfig, stats: Arc<Mutex<Stats>>, output: Arc<Mutex<Output>>) -> Self {
        let fonts = FontStore::new(cfg.font_budget_bytes, MAX_FONT_BYTES);
        let security = security(&cfg);
        Self {
            cfg,
            security,
            stats,
            lib: None,
            renderer: None,
            tracks: HashMap::new(),
            tallies: HashMap::new(),
            selected: None,
            item: 0,
            script: None,
            fonts,
            events: EventBudget::default(),
            default_script: None,
            samples_since_tally: 0,
            output,
            video: (0, 0),
            video_colour: VideoColour::default(),
            mangle: None,
            canvas: Canvas::new(0, 0),
            ladder: Ladder::default(),
            times: FrameTimes::default(),
            window_start: Instant::now(),
            pos_us: 0,
            pos_at: Instant::now(),
            playing: false,
            last_render: None,
            force: false,
            repaint_all: false,
            line_lift: 0.0,
            held: false,
            refusals_in_a_row: 0,
            backoff: None,
            frames_refused: 0,
            max_frame_work: 0,
        }
    }

    fn run(mut self, rx: Receiver<Cmd>) {
        'outer: loop {
            match rx.recv_timeout(self.wait()) {
                Ok(cmd) => {
                    if !self.apply(cmd) {
                        break 'outer;
                    }
                    while let Ok(cmd) = rx.try_recv() {
                        if !self.apply(cmd) {
                            break 'outer;
                        }
                    }
                }
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => break,
            }
            self.tick();
        }
        self.end_item();
        lock(&self.output).sink = None;
    }

    fn active(&self) -> bool {
        self.selected.is_some() && self.renderer.is_some() && lock(&self.output).sink.is_some()
    }

    fn interval(&self) -> Duration {
        FRAME_INTERVAL.max(Duration::from_millis(self.ladder.level().min_interval_ms()))
    }

    fn wait(&self) -> Duration {
        wait_for(
            self.active() && self.playing,
            self.held,
            self.last_render.map(|(at, _)| at.elapsed()),
            self.interval(),
        )
    }

    /// Media time now: the last reported position, advanced by wall time while playing.
    fn media_ms(&self) -> i64 {
        let advance = if self.playing {
            self.pos_at.elapsed().as_micros() as i64
        } else {
            0
        };
        (self.pos_us + advance) / 1000
    }

    fn apply(&mut self, cmd: Cmd) -> bool {
        match cmd {
            Cmd::BeginItem => {
                self.item += 1;
                self.end_item();
            }
            Cmd::AddFont { name, data } => self.add_font(name, data),
            Cmd::AddTrack { key, header } => self.add_track(key, &header),
            Cmd::AddSamples { key, samples } => {
                // An unknown track drops the batch unparsed; stats publish once, not per refusal.
                if self.tracks.contains_key(&key) {
                    let mut refused = false;
                    for (time_us, data) in &samples {
                        refused |= self.add_sample(&key, *time_us, data);
                    }
                    if refused {
                        self.publish();
                    }
                }
            }
            Cmd::AddScript {
                key,
                data,
                item,
                done,
            } => {
                let added = item == self.item && self.add_script(key, &data);
                let _ = done.send(added);
            }
            Cmd::Select(key) => self.select(key),
            Cmd::Unselect(key) => {
                if self.selected.as_ref() == Some(&key) {
                    self.select(None);
                }
            }
            Cmd::Position { pos_us, playing } => {
                self.pos_us = pos_us;
                self.pos_at = Instant::now();
                self.playing = playing;
            }
            Cmd::VideoSize { width, height } => {
                self.video = (width, height);
                if let Some(r) = self.renderer.as_mut().filter(|_| width > 0 && height > 0) {
                    r.set_storage_size(width, height);
                    self.force = true;
                }
            }
            Cmd::VideoColour(colour) => {
                self.video_colour = colour;
                self.update_mangle();
            }
            Cmd::OutputChanged => self.reconfigure(),
            Cmd::LineLift(p) => {
                self.line_lift = p;
                if let Some(r) = self.renderer.as_mut() {
                    r.set_line_position(p);
                    self.force = true;
                }
            }
            Cmd::HoldFrames(held) => {
                self.held = held;
                self.force = true;
                if held {
                    let dirty = self.canvas.clear();
                    self.present(dirty);
                }
            }
            #[cfg(test)]
            Cmd::Stall { entered, release } => {
                let _ = entered.send(());
                let _ = release.recv();
            }
            Cmd::Shutdown => return false,
        }
        true
    }

    fn ensure_lib(&mut self) -> &mut Library {
        if self.lib.is_none() {
            let mut lib = Library::new();
            lib.set_extract_fonts(true);
            self.security.apply_library(&mut lib);
            self.lib = Some(lib);
            self.publish();
        }
        self.lib.get_or_insert_with(Library::new)
    }

    fn add_font(&mut self, name: String, data: Vec<u8>) {
        if self.fonts.offer(name, data) == Admission::Kept && self.renderer.is_some() {
            // A late font for a track already showing: load it now and rebuild the font list.
            self.commit_fonts();
            self.apply_default_font(true);
        }
        self.publish();
    }

    fn add_track(&mut self, key: String, header: &[u8]) {
        if self.tracks.contains_key(&key) || header.len() > MAX_HEADER_BYTES {
            return;
        }
        let mut track = script::new_track();
        self.security.apply_track(&mut track);
        let lib = self.ensure_lib();
        // A panic on hostile input loses this track only, never the render thread.
        let parsed = catch_unwind(AssertUnwindSafe(|| {
            script::process_codec_private(&mut track, lib, header);
            script::configure_prune(&mut track, PRUNE_DELAY_MS);
        }));
        if parsed.is_err() {
            note(format!("track {key}: header parse panicked; track skipped"));
            return;
        }
        let mut tally = Tally::default();
        for line in String::from_utf8_lossy(header).lines() {
            if let Some(text) = line
                .strip_prefix("Dialogue:")
                .and_then(|l| l.splitn(10, ',').nth(9))
            {
                tally.add_event_text(text);
            }
        }
        self.tallies.insert(key.clone(), tally);
        self.tracks.insert(key, track);
    }

    /// True when the event budget refused the sample, so the caller publishes the skip count.
    fn add_sample(&mut self, key: &str, time_us: i64, data: &[u8]) -> bool {
        let Some(chunk) = dialogue::parse_sample(data) else {
            return false;
        };
        let start_ms = time_us / 1000;
        let end_ms = start_ms + chunk.duration_ms;
        let now_ms = self.media_ms();
        // Free ended lines before the budget check, so a full budget never refuses what pruning makes room for.
        if let Some(track) = self.tracks.get_mut(key) {
            let limits = track.input_limits();
            if let Some(deadline) = pressure_prune_deadline(
                track.events().len(),
                track.retained_input_bytes(),
                &limits,
                now_ms,
            ) {
                script::prune_events(track, deadline);
                self.events.prune(deadline);
            }
        }
        if !self.events.fits(chunk.payload.len(), now_ms) {
            return true;
        }
        let Some(track) = self.tracks.get_mut(key) else {
            return false;
        };
        let before = track.events().len();
        let fed = catch_unwind(AssertUnwindSafe(|| {
            script::process_chunk(track, chunk.payload, start_ms, chunk.duration_ms);
        }));
        if fed.is_err() {
            note(format!(
                "track {key}: event at {start_ms} ms panicked; event dropped"
            ));
        }
        // A block delivered again (a seek's landing cluster, a re-fetched line) is a ReadOrder the
        // track already holds and drops, so it costs no budget.
        if track.events().len() == before {
            return false;
        }
        self.events.record(end_ms, chunk.payload.len());
        if let (Some(tally), Ok(text)) = (
            self.tallies.get_mut(key),
            std::str::from_utf8(chunk.payload),
        ) {
            if let Some(t) = text.splitn(9, ',').nth(8) {
                tally.add_event_text(t);
            }
        }
        if self.selected.as_deref() == Some(key) {
            let within_ms = i64::try_from(self.interval().as_millis()).unwrap_or(i64::MAX);
            if shows_within(start_ms, end_ms, now_ms, within_ms) {
                self.force = true;
            }
            self.samples_since_tally += 1;
            if self.samples_since_tally >= RETALLY_EVERY {
                self.samples_since_tally = 0;
                self.apply_default_font(false);
            }
        }
        false
    }

    /// docs/18 §3.2: nothing prunes a script track, since nothing would deliver its lines again.
    fn add_script(&mut self, key: String, data: &[u8]) -> bool {
        if let Some(old) = self.script.take() {
            if self.selected.as_ref() == Some(&old) {
                self.select(None);
            }
            self.tracks.remove(&old);
            self.tallies.remove(&old);
        }
        if self.tracks.contains_key(&key) {
            return false;
        }
        let lib = self.ensure_lib();
        // A panic on hostile input loses this script only, never the render thread.
        let track = match catch_unwind(AssertUnwindSafe(|| script::read_memory(lib, data, None))) {
            Ok(Some(track)) if !track.events().is_empty() => track,
            Ok(_) => return false,
            Err(_) => {
                note(format!("script {key}: parse panicked; script skipped"));
                return false;
            }
        };
        let mut tally = Tally::default();
        for event in track.events() {
            tally.add_event_text(&String::from_utf8_lossy(&event.text));
        }
        self.tallies.insert(key.clone(), tally);
        self.tracks.insert(key.clone(), track);
        self.script = Some(key);
        self.publish();
        true
    }

    fn select(&mut self, key: Option<String>) {
        match key.filter(|k| self.tracks.contains_key(k)) {
            Some(k) => {
                self.selected = Some(k);
                self.ensure_renderer();
                self.apply_default_font(false);
                self.update_mangle();
            }
            None => {
                self.selected = None;
                self.mangle = None;
                let dirty = self.canvas.clear();
                self.present(dirty);
            }
        }
        self.publish();
    }

    fn update_mangle(&mut self) {
        let header = self
            .selected
            .as_ref()
            .and_then(|k| self.tracks.get(k))
            .map(|t| t.ycbcr_matrix);
        self.mangle = header.and_then(|h| colour::mangle(h, self.video_colour));
        self.force = true;
    }

    fn ensure_renderer(&mut self) {
        if self.renderer.is_some() {
            return;
        }
        let mut r = Renderer::new(self.ensure_lib());
        self.security.apply_renderer(&mut r);
        r.set_shaper(ShapingLevel::Complex);
        r.set_hinting(Hinting::None);
        r.set_line_position(self.line_lift);
        // A glyph the style's font lacks comes from an attached font, then the system list.
        r.set_font_fallback(FontFallback {
            embedded: true,
            files: self.cfg.fonts.fallback.clone(),
        });
        if self.video.0 > 0 && self.video.1 > 0 {
            r.set_storage_size(self.video.0, self.video.1);
        }
        self.renderer = Some(r);
        self.commit_fonts();
        self.apply_default_font(true);
        self.reconfigure();
    }

    /// Moves waiting fonts into the library, which keeps its own copy; each original is freed as
    /// soon as it is copied, so the peak is one font over the total, not twice the total.
    fn commit_fonts(&mut self) {
        let pending = self.fonts.take_pending();
        if pending.is_empty() {
            return;
        }
        let lib = self.ensure_lib();
        let mut refused = 0;
        for f in pending {
            refused += usize::from(!lib.add_font(&f.name, &f.data));
        }
        if refused > 0 {
            note(format!(
                "{refused} font(s) refused by the library's font limits"
            ));
        }
    }

    /// (Re)builds the renderer's font list with the default file for the selected track's script.
    fn apply_default_font(&mut self, always: bool) {
        if self.renderer.is_none() {
            return;
        }
        let script = self
            .selected
            .as_ref()
            .and_then(|k| self.tallies.get(k))
            .map_or(Script::Latin, Tally::dominant);
        if !always && self.default_script == Some(script) {
            return;
        }
        let path = self.cfg.fonts.pick(script).and_then(|p| p.to_str());
        if let Some(r) = self.renderer.as_mut() {
            r.set_fonts(path, Some("sans-serif"));
        }
        self.default_script = Some(script);
        self.force = true;
        self.publish();
    }

    fn render_size(&self) -> (i32, i32) {
        let (w, h) = lock(&self.output).size;
        if w <= 0 || h <= 0 {
            return (0, 0);
        }
        let mut s = self.ladder.level().scale() as f64;
        let px = (w as f64 * s) * (h as f64 * s);
        if px > self.cfg.max_render_pixels as f64 {
            s *= (self.cfg.max_render_pixels as f64 / px).sqrt();
        }
        let even = |v: f64| ((v.round() as i32) & !1).max(2);
        (even(w as f64 * s), even(h as f64 * s))
    }

    /// Sizes the canvas and renderer for the window and quality; [`Self::present`] sizes the window.
    fn reconfigure(&mut self) {
        let (w, h) = self.render_size();
        if w == 0 {
            return;
        }
        if (self.canvas.width(), self.canvas.height()) != (w, h) {
            self.canvas = Canvas::new(w, h);
        }
        if let Some(r) = self.renderer.as_mut() {
            r.set_frame_size(w, h);
        }
        self.force = true;
        self.publish();
    }

    fn tick(&mut self) {
        if !self.active() || self.held {
            return;
        }
        let now_ms = self.media_ms();
        if self
            .backoff
            .is_some_and(|(from, until)| (from..until).contains(&now_ms))
        {
            // Counts as a render for pacing, so the loop sleeps an interval instead of spinning.
            self.last_render = Some((Instant::now(), now_ms));
            return;
        }
        if !self.force {
            match self.last_render {
                Some((at, _)) if self.playing && at.elapsed() < self.interval() => return,
                Some((_, ms)) if !self.playing && ms == now_ms => return,
                _ => {}
            }
        }
        self.render(now_ms);
    }

    fn render(&mut self, now_ms: i64) {
        let (Some(lib), Some(renderer), Some(track)) = (
            self.lib.as_ref(),
            self.renderer.as_mut(),
            self.selected.as_ref().and_then(|k| self.tracks.get_mut(k)),
        ) else {
            return;
        };
        let started = Instant::now();
        self.last_render = Some((started, now_ms));
        let force = self.force;
        let mangle = self.mangle;
        let canvas = &mut self.canvas;
        // substation unwinds its own admission refusals; anything else that panics costs this
        // renderer (rebuilt below), never the render thread.
        let outcome = catch_unwind(AssertUnwindSafe(|| {
            let frame = renderer.render_frame(lib, track, now_ms);
            let admission = frame.admission;
            if frame.changed == 0 && !force {
                return (admission, None);
            }
            let images: Vec<Coverage<'_>> = frame
                .images
                .iter()
                .filter(|i| i.w > 0 && i.h > 0)
                .map(|i| Coverage {
                    w: i.w,
                    h: i.h,
                    stride: i.stride,
                    bitmap: i.bitmap,
                    color: mangle.map_or(i.color, |m| m.apply(i.color)),
                    x: i.dst_x,
                    y: i.dst_y,
                })
                .collect();
            (admission, Some(canvas.draw(&images)))
        }));
        let work = renderer.last_frame_work();
        self.max_frame_work = self.max_frame_work.max(work);
        let (admission, dirty) = match outcome {
            Ok(done) => done,
            Err(_) => {
                note(format!("render panicked at {now_ms} ms; renderer rebuilt"));
                self.renderer = None;
                self.ensure_renderer();
                (FrameAdmission::Refused, Some(self.canvas.clear()))
            }
        };
        self.on_admission(admission, now_ms, work);
        let drew = dirty.is_some();
        if let Some(dirty) = dirty {
            self.force = false;
            self.present(dirty);
        }
        // A refused frame's cost says nothing about the track, so it never moves the quality ladder.
        if admission != FrameAdmission::Complete {
            return;
        }
        // Unchanged frames still cost a full layout, so they count toward the ladder too.
        let ms = started.elapsed().as_secs_f32() * 1000.0;
        self.times.push(ms);
        if drew {
            let mut s = lock(&self.stats);
            s.frames_drawn += 1;
            s.last_frame_ms = ms;
        }
        if self.window_start.elapsed() >= WINDOW {
            self.window_start = Instant::now();
            let w = self.times.take_window();
            lock(&self.stats).window_p90_ms = w.p90_ms;
            self.publish();
            if self.ladder.on_window(w, self.cfg.frame_budget_ms).is_some() {
                self.reconfigure();
            }
        }
    }

    /// Logs a change in admission and backs off after refusals (substation SECURITY.md).
    fn on_admission(&mut self, admission: FrameAdmission, now_ms: i64, work: u64) {
        match admission {
            FrameAdmission::Complete => {
                if self.refusals_in_a_row > 0 {
                    note(format!("frames admitted again at {now_ms} ms"));
                }
                self.refusals_in_a_row = 0;
                self.backoff = None;
            }
            FrameAdmission::Refused | FrameAdmission::UnsupportedPanicMode => {
                self.refusals_in_a_row = self.refusals_in_a_row.saturating_add(1);
                self.frames_refused = self.frames_refused.saturating_add(1);
                let wait = refusal_backoff_ms(self.refusals_in_a_row);
                self.backoff = Some((now_ms, now_ms.saturating_add(wait)));
                let memory = self
                    .renderer
                    .as_ref()
                    .map_or(0, Renderer::render_peak_memory_bytes);
                note(format!(
                    "frame {admission:?} at {now_ms} ms (work {work}, peak {memory} B); retry in {wait} ms"
                ));
                self.publish();
            }
            // Only a host cancels a frame, which says nothing about the script: no refusal, no back-off.
            FrameAdmission::Cancelled => {}
        }
    }

    /// Frees the item's tracks, renderer and fonts.
    fn end_item(&mut self) {
        self.tracks.clear();
        self.script = None;
        self.renderer = None;
        // Library fonts include any a script embedded in its own [Fonts] section, so clear them always.
        if let Some(lib) = self.lib.as_mut() {
            lib.clear_fonts();
        }
        self.refusals_in_a_row = 0;
        self.backoff = None;
        self.frames_refused = 0;
        self.max_frame_work = 0;
        self.held = false;
        self.fonts = FontStore::new(self.cfg.font_budget_bytes, MAX_FONT_BYTES);
        self.events = EventBudget::default();
        self.tallies.clear();
        self.selected = None;
        self.mangle = None;
        self.default_script = None;
        self.ladder = Ladder::default();
        self.times = FrameTimes::default();
        self.last_render = None;
        let dirty = self.canvas.clear();
        self.present(dirty);
        self.reconfigure();
        self.publish();
    }

    fn present(&mut self, dirty: Rect) {
        let size = (self.canvas.width(), self.canvas.height());
        if size.0 == 0 {
            return;
        }
        let mut out = lock(&self.output);
        let Output {
            sink: Some(sink),
            configured,
            ..
        } = &mut *out
        else {
            return;
        };
        if *configured != Some(size) {
            if !sink.configure(size.0, size.1) {
                return;
            }
            *configured = Some(size);
            // A new or resized window holds none of the canvas yet.
            self.repaint_all = true;
        }
        let area = if self.repaint_all {
            Rect::new(0, 0, size.0, size.1)
        } else {
            dirty
        };
        let shown = sink.present(&self.canvas, area);
        drop(out);
        self.repaint_all = !shown;
    }

    fn publish(&self) {
        let input_refusals = self.tracks.values().map(Track::input_refusals).sum();
        let (memory, peak) = self.renderer.as_ref().map_or((0, 0), |r| {
            (r.render_memory_bytes(), r.render_peak_memory_bytes())
        });
        let mut s = lock(&self.stats);
        s.renderer_loaded = self.lib.is_some();
        s.track_selected = self.selected.is_some();
        s.quality = self.ladder.level();
        s.render_width = self.canvas.width();
        s.render_height = self.canvas.height();
        s.font_bytes = self.fonts.held_bytes();
        s.fonts_skipped = self.fonts.rejected_over_budget();
        s.events_skipped = self.events.skipped();
        s.default_font_script = self.default_script;
        s.frames_refused = self.frames_refused;
        s.input_refusals = input_refusals;
        s.render_memory_bytes = memory;
        s.render_peak_bytes = peak;
        s.max_frame_work = self.max_frame_work;
    }
}

#[cfg(test)]
mod wait_tests {
    use super::*;

    const I: Duration = Duration::from_millis(33);
    const IDLE: Duration = Duration::from_secs(1);

    #[test]
    fn wait_decision_table() {
        let ms = Duration::from_millis;
        for (playing, held, since, want, what) in [
            (false, false, None, IDLE, "not playing or no track"),
            (false, false, Some(ms(5)), IDLE, "idle with a past render"),
            (true, true, None, IDLE, "held, never rendered: no spin"),
            (true, true, Some(ms(500)), IDLE, "held, overdue: no spin"),
            (false, true, None, IDLE, "held and idle"),
            (true, false, None, Duration::ZERO, "first frame is due now"),
            (true, false, Some(ms(10)), ms(23), "rest of the interval"),
            (true, false, Some(ms(33)), Duration::ZERO, "exactly due"),
            (
                true,
                false,
                Some(ms(500)),
                Duration::ZERO,
                "overdue saturates",
            ),
        ] {
            assert_eq!(wait_for(playing, held, since, I), want, "{what}");
        }
    }

    #[test]
    fn a_slower_ladder_interval_stretches_the_wait() {
        let slow = Duration::from_millis(100);
        assert_eq!(
            wait_for(true, false, Some(Duration::from_millis(10)), slow),
            Duration::from_millis(90)
        );
    }
}
