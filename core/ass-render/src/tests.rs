//! End-to-end engine tests through a buffer sink, with the bundled OFL test font as the default.
#![allow(clippy::unwrap_used)]

use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::{
    Canvas, Engine, EngineConfig, FontCandidates, FrameSink, Rect, Script, VideoColour, VideoMatrix,
};

const HEADER: &str = "[Script Info]\nScriptType: v4.00+\nPlayResX: 640\nPlayResY: 360\nScaledBorderAndShadow: yes\n\n\
[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n\
Style: Default,Noto Sans,36,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,20,1\n\n\
[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";

#[derive(Default)]
struct Captured {
    presents: u32,
    first_dirty: Option<Rect>,
    last_dirty: Rect,
    pixels: Vec<u8>,
    width: i32,
    height: i32,
}

#[derive(Clone, Default)]
struct BufferSink(Arc<Mutex<Captured>>);

impl FrameSink for BufferSink {
    fn configure(&mut self, width: i32, height: i32) -> bool {
        let mut c = self.0.lock().unwrap();
        (c.width, c.height) = (width, height);
        true
    }

    fn present(&mut self, canvas: &Canvas, dirty: Rect) -> bool {
        let mut c = self.0.lock().unwrap();
        c.presents += 1;
        c.first_dirty.get_or_insert(dirty);
        c.last_dirty = dirty;
        c.pixels = canvas.pixels().to_vec();
        true
    }
}

impl BufferSink {
    fn presents(&self) -> u32 {
        self.0.lock().unwrap().presents
    }

    /// Whether only the test still holds this sink, i.e. the engine has released its window.
    fn released(&self) -> bool {
        Arc::strong_count(&self.0) == 1
    }

    /// Bounding box of every pixel with any alpha.
    fn ink(&self) -> Rect {
        let c = self.0.lock().unwrap();
        let mut r = Rect::default();
        for (i, px) in c.pixels.as_chunks::<4>().0.iter().enumerate() {
            if px[3] > 0 {
                let (x, y) = ((i as i32) % c.width, (i as i32) / c.width);
                r = r.union(Rect::new(x, y, x + 1, y + 1));
            }
        }
        r
    }
}

fn config() -> EngineConfig {
    let font = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("testdata/NotoSans-Latin.ttf");
    EngineConfig {
        fonts: FontCandidates {
            by_script: vec![(Script::Latin, font)],
            fallback: Vec::new(),
        },
        ..EngineConfig::default()
    }
}

fn sample(duration_cs: u32, payload: &str) -> Vec<u8> {
    let (s, cs) = (duration_cs / 100, duration_cs % 100);
    format!("Dialogue: 0:00:00:00,0:00:{s:02}:{cs:02},{payload}").into_bytes()
}

fn wait_for(what: &str, mut f: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        if f() {
            return;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    panic!("timed out waiting for {what}");
}

fn showing(engine: &Engine, sink: &BufferSink, text_payload: &str) {
    showing_in(engine, Box::new(sink.clone()), text_payload);
}

fn showing_in(engine: &Engine, sink: Box<dyn FrameSink>, text_payload: &str) {
    engine.begin_item();
    engine.add_track("t".into(), HEADER.as_bytes().to_vec());
    engine.select(Some("t".into()));
    engine.set_sink(Some((sink, 640, 360)));
    engine.add_sample("t".into(), 1_000_000, sample(500, text_payload));
    engine.set_position(2_000_000, false);
}

#[test]
fn dialogue_renders_at_the_bottom_with_the_default_font() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Hello there");
    wait_for("ink", || !sink.ink().is_empty());
    let ink = sink.ink();
    assert!(
        ink.y1 > 300 && ink.y1 <= 345,
        "bottom-aligned with MarginV 20: {ink:?}"
    );
    assert!(ink.x0 > 150 && ink.x1 < 490, "centered: {ink:?}");
    let s = engine.stats();
    assert!(s.renderer_loaded && s.track_selected && s.frames_drawn > 0);
    assert_eq!(s.default_font_script, Some(Script::Latin));
}

#[test]
fn every_sample_of_a_batch_is_fed() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    engine.begin_item();
    engine.add_track("t".into(), HEADER.as_bytes().to_vec());
    engine.select(Some("t".into()));
    engine.set_sink(Some((Box::new(sink.clone()), 640, 360)));
    engine.add_samples(
        "t".into(),
        vec![
            (
                1_000_000,
                sample(500, "1,0,Default,,0,0,0,,{\\an7\\pos(10,10)}Left"),
            ),
            (
                1_500_000,
                sample(500, "2,0,Default,,0,0,0,,{\\an9\\pos(630,10)}Right"),
            ),
        ],
    );
    engine.set_position(2_000_000, false);
    wait_for("both lines", || {
        let ink = sink.ink();
        ink.x0 < 100 && ink.x1 > 540
    });
}

#[test]
fn a_held_overlay_draws_nothing_until_released() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    engine.begin_item();
    engine.hold_frames(true);
    engine.add_track("t".into(), HEADER.as_bytes().to_vec());
    engine.select(Some("t".into()));
    engine.set_sink(Some((Box::new(sink.clone()), 640, 360)));
    engine.add_sample(
        "t".into(),
        1_000_000,
        sample(500, "1,0,Default,,0,0,0,,Waiting for fonts"),
    );
    engine.set_position(2_000_000, false);
    std::thread::sleep(Duration::from_millis(300));
    assert!(sink.ink().is_empty(), "held: nothing drawn");
    assert_eq!(engine.stats().frames_drawn, 0);
    engine.hold_frames(false);
    wait_for("ink after release", || !sink.ink().is_empty());
    engine.hold_frames(true);
    wait_for("erased when held again", || sink.ink().is_empty());
}

#[test]
fn events_outside_their_time_draw_nothing() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Hello");
    wait_for("ink", || !sink.ink().is_empty());
    engine.set_position(9_000_000, false);
    wait_for("erase", || sink.ink().is_empty());
}

#[test]
fn line_lift_moves_dialogue_up() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Lift me");
    wait_for("ink", || !sink.ink().is_empty());
    let before = sink.ink();
    engine.set_line_lift(30.0);
    wait_for("lift", || sink.ink().y1 < before.y1 - 60);
}

#[test]
fn begin_item_erases_the_overlay_and_frees_fonts() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Bye");
    let font = std::fs::read(
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("testdata/NotoSans-Latin.ttf"),
    )
    .unwrap();
    engine.add_font("extra.ttf".into(), font);
    wait_for("font held", || engine.stats().font_bytes > 0);
    wait_for("ink", || !sink.ink().is_empty());
    engine.begin_item();
    wait_for("cleared", || {
        sink.ink().is_empty() && engine.stats().font_bytes == 0
    });
    assert!(!engine.stats().track_selected);
}

#[test]
fn a_4k_surface_renders_at_most_1080p() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    engine.begin_item();
    engine.add_track("t".into(), HEADER.as_bytes().to_vec());
    engine.select(Some("t".into()));
    engine.set_video_size(3840, 2160);
    engine.set_sink(Some((Box::new(sink.clone()), 3840, 2160)));
    // The window is sized at its first copy, after the canvas.
    wait_for("configured", || sink.0.lock().unwrap().width > 0);
    let s = engine.stats();
    assert_eq!((s.render_width, s.render_height), (1920, 1080));
    assert_eq!(sink.0.lock().unwrap().width, 1920);
}

#[test]
fn oversized_events_are_skipped_and_counted() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    let huge = format!("1,0,Default,,0,0,0,,{}", "x".repeat(300 << 10));
    showing(&engine, &sink, &huge);
    wait_for("skip counted", || engine.stats().events_skipped == 1);
    engine.add_sample(
        "t".into(),
        1_000_000,
        sample(500, "2,0,Default,,0,0,0,,Small"),
    );
    wait_for("small line still renders", || !sink.ink().is_empty());
}

#[test]
fn a_batch_of_refused_samples_is_counted_and_an_unknown_track_drops_its_batch() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Hello");
    wait_for("first line", || !sink.ink().is_empty());
    let huge = |i: u32| {
        (
            1_000_000,
            sample(
                500,
                &format!("{i},0,Default,,0,0,0,,{}", "x".repeat(300 << 10)),
            ),
        )
    };
    engine.add_samples("nope".into(), vec![huge(10), huge(11)]);
    engine.add_samples("t".into(), vec![huge(2), huge(3), huge(4)]);
    wait_for("every refusal counted", || {
        engine.stats().events_skipped == 3
    });
    // The unknown track's two refusals never counted.
    assert_eq!(engine.stats().events_skipped, 3);
}

#[test]
fn a_refused_frame_draws_nothing_logs_and_backs_off() {
    let engine = Engine::spawn(EngineConfig {
        frame_work: 1,
        ..config()
    })
    .unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Too costly");
    wait_for("refusal at 2 s", || {
        crate::recent_messages()
            .iter()
            .any(|m| m.contains("Refused at 2000 ms"))
    });
    assert!(sink.ink().is_empty());
    let refused = engine.stats().frames_refused;
    // Paused inside the back-off window: no retry.
    engine.set_position(2_100_000, false);
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(engine.stats().frames_refused, refused);
    // A seek past the window tries again.
    engine.set_position(4_000_000, false);
    wait_for("retry", || engine.stats().frames_refused == refused + 1);
}

#[test]
fn a_perspective_blow_up_stays_inside_the_renderer_budget() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(
        &engine,
        &sink,
        "1,0,Default,,0,0,0,,{\\org(-100000,-100000)\\fs120\\t(\\frx89\\fry89)}Far origin",
    );
    for ms in [1_200_000, 2_500_000, 4_000_000, 5_900_000] {
        engine.set_position(ms, false);
        wait_for("frame", || engine.stats().frames_drawn > 0);
    }
    let s = engine.stats();
    let cap = crate::limits::security(&config())
        .render
        .renderer_memory_bytes;
    assert!(s.render_peak_bytes < cap, "{}", s.render_peak_bytes);
    // The thread is alive and still answers.
    engine.set_position(9_000_000, false);
    wait_for("erase", || sink.ink().is_empty());
}

/// A window whose copies block until `gate` sends or drops, announcing each on `entered`.
struct GatedSink {
    inner: BufferSink,
    entered: SyncSender<()>,
    gate: Receiver<()>,
}

impl FrameSink for GatedSink {
    fn configure(&mut self, width: i32, height: i32) -> bool {
        self.inner.configure(width, height)
    }

    fn present(&mut self, canvas: &Canvas, dirty: Rect) -> bool {
        let _ = self.entered.try_send(());
        let _ = self.gate.recv();
        self.inner.present(canvas, dirty)
    }
}

/// Far longer than any swap takes; reaching it means the call waited for the parked render thread.
const SWAP_LIMIT: Duration = Duration::from_secs(5);

/// Calls `set_sink` on another thread while the render thread is parked; `Some(released)` when it
/// returned within [`SWAP_LIMIT`] (`released`: the old sink was dropped by then), `None` when it waited.
fn set_sink_while_stalled(
    engine: &Engine,
    old: &BufferSink,
    sink: Option<(Box<dyn FrameSink>, i32, i32)>,
) -> Option<bool> {
    let release = engine.stall();
    std::thread::scope(|s| {
        let (done, on_done) = mpsc::channel();
        s.spawn(move || {
            engine.set_sink(sink);
            let _ = done.send(old.released());
        });
        let returned = on_done.recv_timeout(SWAP_LIMIT).ok();
        drop(release);
        returned
    })
}

#[test]
fn detaching_during_a_slow_frame_returns_at_once_and_the_window_is_never_drawn_again() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Hello");
    wait_for("ink", || !sink.ink().is_empty());
    let presents = sink.presents();
    let released = set_sink_while_stalled(&engine, &sink, None);
    assert_eq!(
        released,
        Some(true),
        "detach waited for the render thread, or kept the window"
    );
    engine.set_position(3_000_000, false);
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(sink.presents(), presents);
}

#[test]
fn attaching_during_a_slow_frame_returns_at_once_and_repaints_the_new_window_whole() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    showing(&engine, &sink, "1,0,Default,,0,0,0,,Hello");
    wait_for("ink", || !sink.ink().is_empty());
    let next = BufferSink::default();
    let attach = Some((Box::new(next.clone()) as Box<dyn FrameSink>, 1280, 720));
    let released = set_sink_while_stalled(&engine, &sink, attach);
    assert_eq!(
        released,
        Some(true),
        "attach waited for the render thread, or kept the old window"
    );
    wait_for("ink in the new window", || !next.ink().is_empty());
    let c = next.0.lock().unwrap();
    assert_eq!((c.width, c.height), (1280, 720));
    assert_eq!(c.first_dirty, Some(Rect::new(0, 0, 1280, 720)));
}

#[test]
fn detaching_during_a_copy_waits_for_that_copy_only_then_never_draws_again() {
    let engine = Engine::spawn(config()).unwrap();
    let inner = BufferSink::default();
    let (entered, on_entered) = mpsc::sync_channel(1);
    let (open, gate) = mpsc::sync_channel(0);
    let gated = GatedSink {
        inner: inner.clone(),
        entered,
        gate,
    };
    showing_in(&engine, Box::new(gated), "1,0,Default,,0,0,0,,Copying");
    on_entered.recv_timeout(Duration::from_secs(5)).unwrap();
    let (engine, released) = (&engine, &inner);
    std::thread::scope(|s| {
        let (done, on_done) = mpsc::channel();
        s.spawn(move || {
            engine.set_sink(None);
            let _ = done.send(released.released());
        });
        assert!(
            on_done.recv_timeout(Duration::from_millis(300)).is_err(),
            "detach returned while the window was being written"
        );
        drop(open);
        assert_eq!(on_done.recv_timeout(SWAP_LIMIT), Ok(true));
    });
    let presents = inner.presents();
    engine.set_position(3_000_000, false);
    std::thread::sleep(Duration::from_millis(200));
    assert_eq!(inner.presents(), presents);
}

#[test]
fn lines_delivered_again_cost_no_budget() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    engine.begin_item();
    engine.add_track("t".into(), HEADER.as_bytes().to_vec());
    engine.select(Some("t".into()));
    engine.set_sink(Some((Box::new(sink.clone()), 640, 360)));
    engine.set_position(0, false);
    // 60 lines of 250 KB, far ahead so none shows or ends: 15 MB live, under every input limit.
    let lines: Vec<Vec<u8>> = (1..=60)
        .map(|i| {
            sample(
                500,
                &format!("{i},0,Default,,0,0,0,,{}", "x".repeat(250 << 10)),
            )
        })
        .collect();
    // Seeks back re-deliver the same blocks; charged again, the second pass would pass 32 MB.
    for _pass in 0..3 {
        for line in &lines {
            engine.add_sample("t".into(), 1_000_000_000, line.clone());
        }
    }
    // Queued behind every line above, so once it draws they have all been handled.
    engine.add_sample("t".into(), 0, sample(500, "61,0,Default,,0,0,0,,Probe"));
    wait_for("probe drawn", || !sink.ink().is_empty());
    assert_eq!(engine.stats().events_skipped, 0);
}

/// docs/09: a script's colours follow its `YCbCr Matrix` header onto the video's matrix.
#[test]
fn colours_convert_from_the_header_matrix_to_the_videos() {
    let green_at = |header: &str| {
        let engine = Engine::spawn(config()).unwrap();
        let sink = BufferSink::default();
        engine.begin_item();
        engine.add_track("t".into(), header.as_bytes().to_vec());
        engine.select(Some("t".into()));
        engine.set_video_colour(VideoColour {
            matrix: VideoMatrix::Bt709,
            full_range: false,
            hdr: false,
            height: 1080,
        });
        engine.set_sink(Some((Box::new(sink.clone()), 640, 360)));
        let patch = "1,0,Default,,0,0,0,,{\\an7\\pos(100,100)\\c&H00FF00&\\bord0\\shad0\\p1}m 0 0 l 60 0 60 60 0 60";
        engine.add_sample("t".into(), 1_000_000, sample(500, patch));
        engine.set_position(2_000_000, false);
        wait_for("patch", || !sink.ink().is_empty());
        let c = sink.0.lock().unwrap();
        let i = ((130 * c.width + 130) * 4) as usize;
        (c.pixels[i], c.pixels[i + 1], c.pixels[i + 2])
    };
    assert_eq!(
        green_at(HEADER),
        (0, 215, 0),
        "missing header: BT.601 TV onto BT.709"
    );
    let none = HEADER.replace(
        "ScaledBorderAndShadow: yes",
        "ScaledBorderAndShadow: yes\nYCbCr Matrix: None",
    );
    assert_eq!(green_at(&none), (0, 255, 0), "None keeps the colour");
    let bt709 = HEADER.replace(
        "ScaledBorderAndShadow: yes",
        "ScaledBorderAndShadow: yes\nYCbCr Matrix: TV.709",
    );
    assert_eq!(green_at(&bt709), (0, 255, 0), "matching the video keeps it");
}

fn script(lines: &[&str]) -> Vec<u8> {
    let mut s = HEADER.to_string();
    for line in lines {
        s.push_str(line);
        s.push('\n');
    }
    s.into_bytes()
}

const SCRIPT_LINE: &str = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,From the sidecar";

#[test]
fn a_whole_script_shows_and_still_shows_after_playing_far_past_it() {
    let engine = Engine::spawn(config()).unwrap();
    let sink = BufferSink::default();
    engine.begin_item();
    assert!(engine.add_script("s".into(), script(&[SCRIPT_LINE]), engine.item()));
    engine.select(Some("s".into()));
    engine.set_sink(Some((Box::new(sink.clone()), 640, 360)));
    engine.set_position(2_000_000, false);
    wait_for("ink", || !sink.ink().is_empty());
    // Well past the delay a streamed track prunes after: nothing would deliver these lines again.
    engine.set_position(400_000_000, false);
    wait_for("erase", || sink.ink().is_empty());
    engine.set_position(2_000_000, false);
    wait_for("ink after seeking back", || !sink.ink().is_empty());
}

#[test]
fn a_script_for_an_ended_item_or_without_events_is_refused() {
    let engine = Engine::spawn(config()).unwrap();
    engine.begin_item();
    let item = engine.item();
    engine.begin_item();
    assert!(!engine.add_script("s".into(), script(&[SCRIPT_LINE]), item));
    let item = engine.item();
    assert!(!engine.add_script("s".into(), script(&[]), item));
    assert!(!engine.add_script("s".into(), b"\x00\xff not a script".to_vec(), item));
    assert!(engine.add_script("s".into(), script(&[SCRIPT_LINE]), item));
}

#[test]
fn a_new_script_replaces_the_last_one() {
    let engine = Engine::spawn(config()).unwrap();
    engine.begin_item();
    let item = engine.item();
    assert!(engine.add_script("a".into(), script(&[SCRIPT_LINE]), item));
    engine.select(Some("a".into()));
    wait_for("selected", || engine.stats().track_selected);
    assert!(engine.add_script("b".into(), script(&[SCRIPT_LINE]), item));
    wait_for("the old script deselected", || {
        !engine.stats().track_selected
    });
    engine.select(Some("a".into()));
    engine.select(Some("b".into()));
    wait_for("the new one selectable", || engine.stats().track_selected);
    engine.select(Some("a".into()));
    wait_for("the old one gone", || !engine.stats().track_selected);
}

#[test]
fn unselect_clears_only_its_own_track() {
    let engine = Engine::spawn(config()).unwrap();
    engine.begin_item();
    engine.add_track("t".into(), HEADER.as_bytes().to_vec());
    assert!(engine.add_script("s".into(), script(&[SCRIPT_LINE]), engine.item()));
    engine.select(Some("s".into()));
    // The embedded track's renderer, disabled late, can't clear the sidecar picked meanwhile.
    engine.unselect("t".into());
    engine.set_position(0, false);
    wait_for("still selected", || engine.stats().track_selected);
    engine.unselect("s".into());
    wait_for("deselected", || !engine.stats().track_selected);
}
