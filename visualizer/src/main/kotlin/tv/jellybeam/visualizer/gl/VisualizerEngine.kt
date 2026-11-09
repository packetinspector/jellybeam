package tv.jellybeam.visualizer.gl

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tv.jellybeam.visualizer.FeatureSource
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.color.FlashLimiter
import tv.jellybeam.visualizer.color.GainCurve
import tv.jellybeam.visualizer.color.PaletteCycle
import tv.jellybeam.visualizer.color.Palettes
import tv.jellybeam.visualizer.pattern.AutoScheduler
import tv.jellybeam.visualizer.pattern.Crossfade
import tv.jellybeam.visualizer.pattern.FrameParams
import tv.jellybeam.visualizer.pattern.Pattern
import tv.jellybeam.visualizer.pattern.PatternCycle
import tv.jellybeam.visualizer.pattern.PatternParams
import tv.jellybeam.visualizer.pattern.PatternSpecs

/**
 * docs/30 §6: one visualizer surface's GL thread, EGL context and vsync loop. Exists only between
 * [start] and [stop] (surface created to destroyed), so a closed visualizer holds no thread, context
 * or callback. Every GL call happens on its own thread.
 */
class VisualizerEngine(
    private val assets: AssetManager,
    private val source: FeatureSource,
    initialPattern: Pattern,
    /** The five palette families for the user's accent, [Palettes.build] layout, in [PaletteFamily] order. */
    initialPalettes: List<FloatArray>,
    /** Main-thread callback when the source switches between analysable and encoded audio (D10). */
    private val onEncodedChanged: (Boolean) -> Unit = {},
    /** The display's vsync period; a gap is a miss only against the real refresh rate (D7). */
    vsyncNanos: Long = Pacer.DEFAULT_VSYNC_NANOS,
    /** Main-thread callback when the pattern changes: by a Left/Right step ([manual]) or by itself (docs/30 §5). */
    private val onPatternChanged: (pattern: Pattern, manual: Boolean) -> Unit = { _, _ -> },
) {
    private val thread = HandlerThread("viz-gl")
    private lateinit var handler: Handler
    private lateinit var choreographer: Choreographer
    private val main = Handler(android.os.Looper.getMainLooper())

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderer: GlRenderer? = null

    private val features = Features()
    private val activeA = GlRenderer.Active()
    private val activeB = GlRenderer.Active()

    /** One params set per pattern, so a crossfade's incoming pattern keeps its travel when it takes over. */
    private val paramsByPattern = Array(Pattern.entries.size) { FrameParams() }
    private val pacer = Pacer(vsyncNanos)
    private var pattern = initialPattern
    private var palettes = initialPalettes
    private val cycle = PaletteCycle()
    private val limiter = FlashLimiter()
    private val scheduler = AutoScheduler()
    private val crossfade = Crossfade()
    private var strobeGain = 1f
    private var lastStepMs = Long.MIN_VALUE / 2

    /** docs/30 §4: the limiter picks what each frame shows from that frame's measured gain curve. */
    private val gainPolicy = GlRenderer.GainPolicy { shown ->
        if (VizDebug.limiterOff) {
            1f
        } else {
            val want = limiter.limit(features.timeS, shown[GainCurve.ONE], shown[0], shown[shown.size - 1])
            GainCurve.solve(shown, want)
        }
    }
    private var resetLayer = false
    private var scheduled = false
    private var frozenDrawn = false
    private var dirty = true
    /** Null until the first frame, so a new engine always reports the state once (D10 text never sticks). */
    private var encoded: Boolean? = null
    private var stopped = false

    /** Main thread only: stop() ran, so a second call (or a post after it) does nothing. */
    private var stopRequested = false
    private var lastTimeS = 0f

    @Volatile
    private var captureRequest: ((ByteBuffer, Int, Int) -> Unit)? = null
    private var width = 1
    private var height = 1

    private val frameCallback = Choreographer.FrameCallback { doFrame(it) }
    @Volatile
    private var wokeAtNanos = 0L
    private val wake = Runnable {
        wokeAtNanos = System.nanoTime()
        handler.post(wakeOnThread)
    }
    private val wakeOnThread = Runnable {
        frozenDrawn = false
        schedule()
    }

    /** Main thread: the surface exists; creates the thread and context and starts drawing. */
    fun start(surface: Surface, w: Int, h: Int) {
        thread.start()
        VizDebug.liveThreads.incrementAndGet()
        handler = Handler(thread.looper)
        handler.post {
            choreographer = Choreographer.getInstance()
            width = w
            height = h
            // A device that can't give a context or load the shaders shows the bare surface, never a crash.
            val r = try {
                if (initEgl(surface)) GlRenderer(assets).also { VizDebug.shaderLogs = it.create() } else null
            } catch (e: Exception) {
                Log.w(TAG, "visualizer unavailable: ${e.javaClass.simpleName}")
                null
            }
            if (r == null) {
                releaseEgl()
                return@post
            }
            r.resize(w, h)
            uploadPalettes(r)
            VizDebug.gpuBytes.addAndGet(r.gpuBytes)
            renderer = r
            source.open(wake)
            schedule()
        }
    }

    /** Test hook (docs/30 §9 T22): whether pacing has dropped to 30 fps. */
    val halved: Boolean get() = pacer.halved

    /** Main thread: the surface changed size. */
    fun resize(w: Int, h: Int) = post {
        width = w
        height = h
        renderer?.resize(w, h)
        dirty = true
        schedule()
    }

    /**
     * Left/Right (docs/30 §5): one step from the pattern on screen (a crossfade's target counts), at once,
     * no crossfade, a fresh layer and a fresh count to the next automatic change. The engine owns the
     * pattern, so a step can never be lost to an automatic change racing it.
     */
    fun step(delta: Int) = post {
        // Every step is a hard cut the limiter can't soften, so steps are at most about three a second.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStepMs < MIN_STEP_MS) return@post
        lastStepMs = now
        var p = if (crossfade.active) crossfade.to else pattern
        repeat(kotlin.math.abs(delta)) { p = if (delta > 0) PatternCycle.next(p) else PatternCycle.previous(p) }
        pattern = p
        crossfade.cancel()
        scheduler.reset()
        resetLayer = true
        dirty = true
        schedule()
        main.post { if (!stopRequested) onPatternChanged(p, true) }
    }

    fun setPalettes(p: List<FloatArray>) = post {
        palettes = p
        renderer?.let(::uploadPalettes)
        dirty = true
        schedule()
    }

    /** E6: the "Change patterns automatically" setting. */
    fun setAutoChange(on: Boolean) {
        // Before start the GL thread does not exist yet, so the field is set directly.
        if (!thread.isAlive) {
            scheduler.enabled = on
            return
        }
        post {
            scheduler.enabled = on
            if (!on) scheduler.reset()
        }
    }

    /** Test hook (docs/30 §9 L8): starts the automatic transition now, as the scheduler would. */
    fun forceTransition() = post {
        if (!crossfade.active) startAuto()
    }

    /** Test hook (docs/30 §9): delivers the next frame drawn from new audio (RGBA, bottom row first) on the GL thread. */
    fun capture(onFrame: (ByteBuffer, Int, Int) -> Unit) = post {
        captureRequest = onFrame
    }

    /**
     * Main thread: the surface is going away. Blocks until the GL thread has released the surface,
     * as SurfaceHolder.Callback.surfaceDestroyed requires, then ends the thread.
     */
    fun stop() {
        if (stopRequested || !::handler.isInitialized) return
        stopRequested = true
        main.removeCallbacksAndMessages(null)
        val done = CountDownLatch(1)
        handler.post {
            stopped = true
            if (scheduled) choreographer.removeFrameCallback(frameCallback)
            scheduled = false
            if (renderer != null) source.close()
            renderer?.let { VizDebug.gpuBytes.addAndGet(-it.gpuBytes) }
            renderer?.release()
            renderer = null
            releaseEgl()
            done.countDown()
        }
        done.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
        thread.join(STOP_TIMEOUT_MS)
        VizDebug.liveThreads.decrementAndGet()
    }

    private fun post(block: () -> Unit) {
        if (stopRequested || !thread.isAlive) return
        handler.post(block)
    }

    private fun schedule() {
        if (scheduled || stopped || renderer == null) return
        scheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun doFrame(frameNanos: Long) {
        scheduled = false
        val woke = wokeAtNanos
        if (woke != 0L) {
            wokeAtNanos = 0L
            VizDebug.wakeLatencyNanos = System.nanoTime() - woke
        }
        val r = renderer ?: return
        if (!pacer.onVsync(frameNanos, VizDebug.slowFrameHook)) {
            schedule()
            return
        }
        val nowEncoded = source.encoded
        if (nowEncoded != encoded) {
            encoded = nowEncoded
            dirty = true
            main.post { if (!stopRequested) onEncodedChanged(nowEncoded) }
        }
        val t0 = System.nanoTime()
        val changed = if (nowEncoded) false else source.update(frameNanos, features)
        val t1 = System.nanoTime()
        if (changed || dirty) {
            if (nowEncoded) {
                // D10's still: floor bars, so the history row the bars read must be cleared too.
                features.clear()
                r.pushHistory(features)
            }
            // Motion advances by audio time only, so a redraw (switch, resize) or a pause moves nothing.
            val dtS = (features.timeS - lastTimeS).coerceIn(0f, 0.1f)
            lastTimeS = features.timeS
            if (resetLayer) {
                // The flash budget carries over, so fast Left/Right presses can't buy extra flashes.
                r.resetLayer()
                resetLayer = false
            }
            if (changed) {
                r.pushHistory(features)
                // Per-frame events (kick, beat, phrase) are counted once, on the frame that brought them.
                if (features.kick) strobeGain = limiter.admit(features.timeS, STROBE_LUM, false)
                if (!nowEncoded) advancePatterns(dtS)
            }
            val kick = features.kickPulse * (if (VizDebug.limiterOff) 1f else strobeGain)
            val a = activeA.also { it.pattern = if (nowEncoded) Pattern.BARS else if (crossfade.active) crossfade.from else pattern }
            a.params = paramsByPattern[a.pattern.ordinal]
            a.weight = if (crossfade.active && !nowEncoded) 1f - crossfade.t else 1f
            PatternParams.update(a.pattern, features, dtS, a.params, kick)
            val b = if (crossfade.active && !nowEncoded) {
                activeB.pattern = crossfade.to
                activeB.params = paramsByPattern[crossfade.to.ordinal]
                activeB.weight = crossfade.t
                PatternParams.update(activeB.pattern, features, dtS, activeB.params, kick)
                activeB
            } else {
                null
            }
            val famA = PatternSpecs.spec(a.pattern).family.ordinal
            val famB = if (b != null) PatternSpecs.spec(b.pattern).family.ordinal else famA
            val phrase = changed && features.phraseTick
            // A phrase step that would move any colour into or out of saturated red counts as a red flash.
            val stepRed = phrase && (Palettes.stepIsRed(palettes[famA], cycle.offset, PaletteCycle.STEP) ||
                Palettes.stepIsRed(palettes[famB], cycle.offset, PaletteCycle.STEP))
            cycle.update(dtS, features.loudShort, phrase, limiter, features.timeS, stepRed)
            r.draw(features, a, b, cycle.offset, kick, dtS, gainPolicy)
            // CPU submission only: the swap waits on the GPU, which T24 leaves to the TV (docs/30 §9).
            val t2 = System.nanoTime()
            if (changed) {
                captureRequest?.let {
                    captureRequest = null
                    it(r.readPixels(), width, height)
                }
            }
            // A lost surface or context stops the loop; surfaceDestroyed follows and ends the engine.
            if (!EGL14.eglSwapBuffers(display, eglSurface)) stopped = true
            dirty = false
            VizDebug.framesRendered.incrementAndGet()
            val n = VizDebug.timingCount.getAndIncrement()
            val i = (n % VizDebug.TIMING_FRAMES).toInt()
            VizDebug.analyzeNanos[i] = t1 - t0
            VizDebug.statsWaitNanos[i] = r.statsWaitNanos
            VizDebug.submitNanos[i] = t2 - t1 - r.statsWaitNanos
        }
        val running = !nowEncoded && source.running
        if (!running) frozenDrawn = frozenDrawn || !dirty
        if (pacer.wantsFrames(running, frozenDrawn)) {
            schedule()
        } else {
            pacer.onStopped()
        }
    }

    /** E6: auto changes start crossfades; a finished one makes its target the pattern. */
    private fun advancePatterns(dtS: Float) {
        if (crossfade.advance(dtS)) {
            pattern = crossfade.to
            scheduler.reset()
        }
        if (!crossfade.active && scheduler.update(features, dtS)) startAuto()
    }

    private fun startAuto() {
        val next = PatternCycle.next(pattern)
        crossfade.start(pattern, next)
        scheduler.reset()
        VizDebug.autoStarts.incrementAndGet()
        VizDebug.autoStartTiming = VizDebug.timingCount.get()
        main.post { if (!stopRequested) onPatternChanged(next, false) }
    }

    private fun uploadPalettes(r: GlRenderer) {
        val bytes = ByteArray(Palettes.POSITIONS * Palettes.LEVELS * palettes.size * 4)
        Palettes.toRgba8(palettes, bytes)
        r.setPalettes(bytes)
    }

    /** True with a current ES 3 context on [surface]; false leaves whatever was made for [releaseEgl]. */
    private fun initEgl(surface: Surface): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        // The ES 3 bit needs EGL_KHR_create_context; an ES 2 config still takes a version-3 context.
        val config = chooseConfig(EGL_OPENGL_ES3_BIT) ?: chooseConfig(EGL14.EGL_OPENGL_ES2_BIT) ?: return false
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        VizDebug.liveContexts.incrementAndGet()
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    private fun chooseConfig(renderable: Int): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
    }

    private fun releaseEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, context)
            VizDebug.liveContexts.decrementAndGet()
        }
        EGL14.eglReleaseThread()
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        /** EGL_OPENGL_ES3_BIT_KHR; EGL14 has no constant for it. */
        const val EGL_OPENGL_ES3_BIT = 0x40
        const val STOP_TIMEOUT_MS = 2_000L
        const val TAG = "Visualizer"

        /** The luminance a kick strobe is assumed to add, so each one asks the flash limiter first. */
        const val STROBE_LUM = 0.15f
        const val MIN_STEP_MS = 350L
    }
}
