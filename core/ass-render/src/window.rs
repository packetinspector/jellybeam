//! Android output: CPU-locks the overlay `Surface`'s `ANativeWindow` and copies only the dirty
//! rectangle; `Surface` copies the rest back from the previous buffer.

use std::ffi::c_void;
use std::ptr;

use ndk_sys::{
    ANativeWindow, ANativeWindow_Buffer, ANativeWindow_fromSurface, ANativeWindow_lock,
    ANativeWindow_release, ANativeWindow_setBuffersGeometry, ANativeWindow_unlockAndPost, ARect,
};

use crate::compositor::{Canvas, Rect};
use crate::engine::FrameSink;

/// `WINDOW_FORMAT_RGBA_8888`: byte order R, G, B, A, premultiplied as SurfaceFlinger expects.
const RGBA_8888: i32 = 1;

pub struct NativeWindowSink {
    window: *mut ANativeWindow,
}

// SAFETY: an ANativeWindow reference may be used from any thread; the engine uses it from one.
unsafe impl Send for NativeWindowSink {}

impl NativeWindowSink {
    /// Acquires the window behind a `android.view.Surface`; returns it as an opaque handle.
    ///
    /// # Safety
    /// `env` must be the calling thread's `JNIEnv*` and `surface` a live local reference.
    pub unsafe fn acquire(env: *mut c_void, surface: *mut c_void) -> i64 {
        ANativeWindow_fromSurface(env.cast(), surface.cast()) as i64
    }

    /// Takes ownership of a handle from [`Self::acquire`]; dropping the sink releases it.
    ///
    /// # Safety
    /// `handle` must come from [`Self::acquire`] and not be used again by the caller.
    pub unsafe fn adopt(handle: i64) -> Option<Self> {
        let window = handle as *mut ANativeWindow;
        (!window.is_null()).then_some(Self { window })
    }
}

impl Drop for NativeWindowSink {
    fn drop(&mut self) {
        // SAFETY: we own exactly one reference.
        unsafe { ANativeWindow_release(self.window) };
    }
}

impl FrameSink for NativeWindowSink {
    fn configure(&mut self, width: i32, height: i32) -> bool {
        // SAFETY: live window; the compositor scales this buffer to the view's size.
        unsafe { ANativeWindow_setBuffersGeometry(self.window, width, height, RGBA_8888) == 0 }
    }

    fn present(&mut self, canvas: &Canvas, dirty: Rect) -> bool {
        if dirty.is_empty() {
            return true;
        }
        let mut buf = ANativeWindow_Buffer {
            width: 0,
            height: 0,
            stride: 0,
            format: 0,
            bits: ptr::null_mut(),
            reserved: [0; 6],
        };
        let mut rect = ARect {
            left: dirty.x0,
            top: dirty.y0,
            right: dirty.x1,
            bottom: dirty.y1,
        };
        // SAFETY: live window; `rect` may grow to what this buffer needs redrawn.
        if unsafe { ANativeWindow_lock(self.window, &mut buf, &mut rect) } != 0
            || buf.bits.is_null()
        {
            return false;
        }
        let r = Rect::new(rect.left, rect.top, rect.right, rect.bottom).clip(
            buf.width.min(canvas.width()),
            buf.height.min(canvas.height()),
        );
        if !r.is_empty() {
            let src = canvas.pixels();
            let src_row = canvas.width() as usize * 4;
            let dst_row = buf.stride as usize * 4;
            let span = (r.x1 - r.x0) as usize * 4;
            for y in r.y0..r.y1 {
                let s = y as usize * src_row + r.x0 as usize * 4;
                let d = y as usize * dst_row + r.x0 as usize * 4;
                // SAFETY: `r` is inside both the canvas and the locked buffer.
                unsafe {
                    ptr::copy_nonoverlapping(
                        src[s..s + span].as_ptr(),
                        buf.bits.cast::<u8>().add(d),
                        span,
                    )
                };
            }
        }
        // SAFETY: paired with the successful lock above.
        unsafe { ANativeWindow_unlockAndPost(self.window) == 0 }
    }
}
