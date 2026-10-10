//! Full ASS/SSA rendering. Rust owns track lifecycle, fonts, budgets and the output frame;
//! substation, the pure-Rust libass port, rasterizes.

// substation's work and memory admission unwinds out of a refused frame (its SECURITY.md).
#[cfg(panic = "abort")]
compile_error!("ass-render needs panic = \"unwind\" for substation's frame admission");

mod budget;
mod colour;
mod compositor;
mod dialogue;
mod engine;
mod events;
mod fonts;
mod limits;
mod script;
#[cfg(target_os = "android")]
mod window;

pub use budget::Quality;
pub use colour::{VideoColour, VideoMatrix};
pub use compositor::{Canvas, Rect};
pub use engine::{Engine, EngineConfig, FrameSink, Stats};
pub use limits::MAX_SCRIPT_BYTES;
pub use script::{FontCandidates, Script};
#[cfg(target_os = "android")]
pub use window::NativeWindowSink;

use std::collections::VecDeque;
#[cfg(target_os = "android")]
use std::ffi::{c_char, CString};
use std::sync::{Mutex, MutexGuard};

/// Poisoning only means a panicking holder; the guarded data is still usable.
pub(crate) fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

static LOG: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
const LOG_KEEP: usize = 32;

/// The engine's last admission and failure notes, oldest first, for diagnostics.
pub fn recent_messages() -> Vec<String> {
    lock(&LOG).iter().cloned().collect()
}

pub(crate) fn note(message: String) {
    #[cfg(target_os = "android")]
    android_log(&message);
    let mut log = lock(&LOG);
    if log.len() == LOG_KEEP {
        log.pop_front();
    }
    log.push_back(message);
}

/// Mirrors a note to logcat as `JellybeamAss`, so admission changes show without the stats sheet.
#[cfg(target_os = "android")]
fn android_log(message: &str) {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
    }
    const ANDROID_LOG_INFO: i32 = 4;
    let Ok(text) = CString::new(message.replace('\0', "")) else {
        return;
    };
    // SAFETY: both pointers are NUL-terminated and outlive the call.
    unsafe { __android_log_write(ANDROID_LOG_INFO, c"JellybeamAss".as_ptr(), text.as_ptr()) };
}

#[cfg(test)]
mod tests;
