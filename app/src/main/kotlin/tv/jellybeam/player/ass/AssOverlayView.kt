package tv.jellybeam.player.ass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import uniffi.jellybeam_core.AssOverlay

/** JNI pair in `core/ffi/src/ass.rs`: a `Surface`'s native window as an opaque handle. */
object AssSurfaces {
    init {
        // JNA loads the core for UniFFI; JNI symbol lookup needs it loaded through the classloader too.
        System.loadLibrary("jellybeam_core")
    }

    /** The handle is adopted (and later released) by `AssOverlay.attachSurface`; 0 if the surface is gone. */
    external fun nativeAcquire(surface: Surface): Long
}

/**
 * The surface Rust draws ASS frames into, stacked between the video and the app window so the OSD
 * always draws over styled subtitles. Sized to the video by its parent (Media3's content frame);
 * shown whenever full styling is on, and blanked by Rust while no styled track is showing.
 */
@SuppressLint("ViewConstructor") // built in code by PlayerHolder only, never inflated
class AssOverlayView(context: Context, private val overlay: AssOverlay) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isFocusable = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val handle = AssSurfaces.nativeAcquire(holder.surface)
        if (handle != 0L) overlay.attachSurface(handle, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Returns once Rust has released the window, as SurfaceHolder requires; never waits for a frame.
        overlay.detachSurface()
    }
}
