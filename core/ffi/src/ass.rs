//! Kotlin's handle on the Rust ASS overlay (`ass_render::Engine`): Media3 feeds tracks, samples
//! and fonts in; a surface handle from the JNI pair below takes frames out.

use std::sync::Arc;

use ass_render::{Engine, EngineConfig, FontCandidates, Script, VideoColour, VideoMatrix};

use crate::CoreError;

/// One per process, owned by `PlayerHolder`; each played item starts with [`Self::begin_item`].
#[derive(uniffi::Object)]
pub struct AssOverlay {
    engine: Engine,
    font_limits: AssFontLimits,
}

/// Font caps in bytes: one file, and everything one item may load.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq)]
pub struct AssFontLimits {
    pub max_font_bytes: u64,
    pub total_bytes: u64,
}

/// One Media3 SSA sample: its media time and payload.
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct AssSample {
    pub time_us: i64,
    pub data: Vec<u8>,
}

/// The video's colour matrix (Media3 `ColorInfo.colorSpace`); `Unknown` when not signalled.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum AssVideoMatrix {
    Bt601,
    Bt709,
    Bt2020,
    Unknown,
}

#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct AssOverlayStats {
    pub renderer_loaded: bool,
    pub track_selected: bool,
    pub frames_drawn: u64,
    pub last_frame_ms: f32,
    pub window_p90_ms: f32,
    /// `Full`, `Reduced`, `Low` or `Minimal` -- see `ass_render::Quality`.
    pub quality: String,
    pub render_width: i32,
    pub render_height: i32,
    pub font_bytes: u64,
    pub fonts_skipped: u32,
    pub events_skipped: u32,
    pub default_font_script: Option<String>,
    pub frames_refused: u32,
    pub input_refusals: u64,
    pub render_memory_bytes: u64,
    pub render_peak_bytes: u64,
    pub max_frame_work: u64,
    pub recent_messages: Vec<String>,
}

#[uniffi::export]
impl AssOverlay {
    #[uniffi::constructor]
    pub fn new() -> Result<Arc<Self>, CoreError> {
        let fonts = if cfg!(target_os = "android") {
            FontCandidates::android(|p| p.exists())
        } else {
            FontCandidates::default()
        };
        let config = EngineConfig {
            fonts,
            ..EngineConfig::default()
        };
        let (max_font, total) = config.font_limits();
        let font_limits = AssFontLimits {
            max_font_bytes: max_font as u64,
            total_bytes: total as u64,
        };
        let engine = Engine::spawn(config).map_err(|e| CoreError::Cache {
            detail: format!("ass render thread: {e}"),
        })?;
        Ok(Arc::new(Self {
            engine,
            font_limits,
        }))
    }

    /// docs/09: the font caps the player's fetch must apply, the same ones the overlay enforces.
    pub fn font_limits(&self) -> AssFontLimits {
        self.font_limits
    }

    /// Frees the last item's tracks, renderer and fonts and erases the overlay.
    pub fn begin_item(&self) {
        self.engine.begin_item();
    }

    /// The current item, captured before a sidecar fetch so a script can't land on the next one.
    pub fn item(&self) -> u64 {
        self.engine.item()
    }

    /// A Matroska font attachment; held, not loaded, until a styled track is shown.
    pub fn add_font(&self, name: String, data: Vec<u8>) {
        self.engine.add_font(name, data);
    }

    /// A track's `[Script Info]`/`[V4+ Styles]` header (Media3 `initializationData[1]`).
    pub fn add_track(&self, key: String, header: Vec<u8>) {
        self.engine.add_track(key, header);
    }

    /// docs/13: Media3 SSA samples in stream order, in one call: a heavy script holds tens of
    /// thousands in its first seconds, and a call each held the first frame.
    pub fn add_samples(&self, key: String, samples: Vec<AssSample>) {
        let samples = samples.into_iter().map(|s| (s.time_us, s.data)).collect();
        self.engine.add_samples(key, samples);
    }

    pub fn select(&self, key: Option<String>) {
        self.engine.select(key);
    }

    /// Deselects `key` only if no other track was selected since (docs/18 §3.2).
    pub fn unselect(&self, key: String) {
        self.engine.unselect(key);
    }

    pub fn set_position(&self, position_us: i64, playing: bool) {
        self.engine.set_position(position_us, playing);
    }

    pub fn set_video_size(&self, width: i32, height: i32) {
        self.engine.set_video_size(width, height);
    }

    /// docs/13: the video's colour space, for the selected track's `YCbCr Matrix` conversion.
    pub fn set_video_colour(
        &self,
        matrix: AssVideoMatrix,
        full_range: bool,
        hdr: bool,
        height: i32,
    ) {
        let matrix = match matrix {
            AssVideoMatrix::Bt601 => VideoMatrix::Bt601,
            AssVideoMatrix::Bt709 => VideoMatrix::Bt709,
            AssVideoMatrix::Bt2020 => VideoMatrix::Bt2020,
            AssVideoMatrix::Unknown => VideoMatrix::Unknown,
        };
        self.engine.set_video_colour(VideoColour {
            matrix,
            full_range,
            hdr,
            height,
        });
    }

    /// docs/12 §16: percent the bottom dialogue rises while the OSD is up.
    pub fn set_line_lift(&self, percent: f64) {
        self.engine.set_line_lift(percent);
    }

    /// docs/09: true while a styled track's fonts are still arriving, so it never draws in stand-ins.
    pub fn hold_frames(&self, held: bool) {
        self.engine.hold_frames(held);
    }

    /// Adopts a window handle from `AssSurfaces.nativeAcquire`; the overlay releases it.
    pub fn attach_surface(&self, handle: i64, width: i32, height: i32) {
        #[cfg(target_os = "android")]
        {
            // SAFETY: Kotlin passes each nativeAcquire handle here exactly once.
            if let Some(sink) = unsafe { ass_render::NativeWindowSink::adopt(handle) } {
                self.engine.set_sink(Some((Box::new(sink), width, height)));
            }
        }
        #[cfg(not(target_os = "android"))]
        let _ = (handle, width, height);
    }

    /// Returns once the window is released, so the surface may then be destroyed; waits at most for
    /// one copy into the window, never for a frame.
    pub fn detach_surface(&self) {
        self.engine.set_sink(None);
    }

    pub fn stats(&self) -> AssOverlayStats {
        let s = self.engine.stats();
        AssOverlayStats {
            renderer_loaded: s.renderer_loaded,
            track_selected: s.track_selected,
            frames_drawn: s.frames_drawn,
            last_frame_ms: s.last_frame_ms,
            window_p90_ms: s.window_p90_ms,
            quality: format!("{:?}", s.quality),
            render_width: s.render_width,
            render_height: s.render_height,
            font_bytes: s.font_bytes as u64,
            fonts_skipped: s.fonts_skipped,
            events_skipped: s.events_skipped,
            default_font_script: s.default_font_script.map(|x: Script| format!("{x:?}")),
            frames_refused: s.frames_refused,
            input_refusals: s.input_refusals,
            render_memory_bytes: s.render_memory_bytes as u64,
            render_peak_bytes: s.render_peak_bytes as u64,
            max_frame_work: s.max_frame_work,
            recent_messages: ass_render::recent_messages(),
        }
    }
}

impl AssOverlay {
    /// docs/18 §3.2: a whole sidecar script for `item`; blocks until the render thread has parsed it.
    pub(crate) fn add_script(&self, key: String, data: Vec<u8>, item: u64) -> bool {
        self.engine.add_script(key, data, item)
    }
}

/// `tv.jellybeam.player.ass.AssSurfaces.nativeAcquire(Surface): Long` -- 0 when the surface is gone.
#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_tv_jellybeam_player_ass_AssSurfaces_nativeAcquire(
    env: *mut std::ffi::c_void,
    _this: *mut std::ffi::c_void,
    surface: *mut std::ffi::c_void,
) -> i64 {
    ass_render::NativeWindowSink::acquire(env, surface)
}
