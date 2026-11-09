package tv.jellybeam.visualizer.lab

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.gl.VisualizerEngine
import tv.jellybeam.visualizer.gl.VizDebug
import tv.jellybeam.visualizer.pattern.Pattern

/** One captured frame: RGBA, top row first. */
class Frame(val width: Int, val height: Int, val rgba: ByteArray) {
    fun r(x: Int, y: Int) = rgba[(y * width + x) * 4].toInt() and 0xFF
    fun g(x: Int, y: Int) = rgba[(y * width + x) * 4 + 1].toInt() and 0xFF
    fun b(x: Int, y: Int) = rgba[(y * width + x) * 4 + 2].toInt() and 0xFF

    /** Mean relative luminance (WCAG) over every pixel, 0..1. */
    fun meanLuminance(): Double {
        val lin = LINEAR
        var sum = 0.0
        for (i in 0 until width * height) {
            val o = i * 4
            sum += 0.2126 * lin[rgba[o].toInt() and 0xFF] + 0.7152 * lin[rgba[o + 1].toInt() and 0xFF] + 0.0722 * lin[rgba[o + 2].toInt() and 0xFF]
        }
        return sum / (width * height)
    }

    fun toBitmap(): Bitmap {
        val px = IntArray(width * height)
        for (i in px.indices) {
            val o = i * 4
            px[i] = (0xFF shl 24) or ((rgba[o].toInt() and 0xFF) shl 16) or ((rgba[o + 1].toInt() and 0xFF) shl 8) or (rgba[o + 2].toInt() and 0xFF)
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private val LINEAR = DoubleArray(256) { c -> val v = c / 255.0; if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }

        /** From glReadPixels (rows bottom-up) to top-down. */
        fun fromGl(buf: ByteBuffer, w: Int, h: Int): Frame {
            val bytes = ByteArray(w * h * 4)
            for (y in 0 until h) {
                buf.position((h - 1 - y) * w * 4)
                buf.get(bytes, y * w * 4, w * 4)
            }
            return Frame(w, h, bytes)
        }

        fun of(bmp: Bitmap): Frame {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val out = ByteArray(px.size * 4)
            for (i in px.indices) {
                out[i * 4] = (px[i] shr 16).toByte()
                out[i * 4 + 1] = (px[i] shr 8).toByte()
                out[i * 4 + 2] = px[i].toByte()
                out[i * 4 + 3] = 0xFF.toByte()
            }
            return Frame(bmp.width, bmp.height, out)
        }
    }
}

/** Launches [LabActivity] drawing [pattern] from a fresh [LabSource]; closes on [close]. */
class Lab(pattern: Pattern = Pattern.BARS, autoChange: Boolean = false) : AutoCloseable {
    val source = LabSource()
    val scenario: ActivityScenario<LabActivity>

    init {
        LabActivity.Harness.pattern = pattern
        LabActivity.Harness.autoChange = autoChange
        LabActivity.Harness.sourceFactory = { source }
        val frames0 = VizDebug.framesRendered.get()
        scenario = ActivityScenario.launch(LabActivity::class.java)
        await("first frame") { engine() != null && VizDebug.framesRendered.get() > frames0 }
    }

    fun engine(): VisualizerEngine? {
        val ref = AtomicReference<VisualizerEngine?>()
        scenario.onActivity { ref.set(it.view.currentEngine) }
        return ref.get()
    }

    /** Plays [preset] for [frames] updates and waits until the engine has drawn them and gone idle. */
    fun settle(preset: Preset, frames: Int = 60) {
        val target = source.updates + frames
        source.play(LabSignals.features(preset), frames)
        await("$frames updates", 30_000) { source.updates >= target }
        waitIdle()
    }

    /** Idle = no new frame across three 100 ms polls, which a swiftshader hiccup can't fake. */
    /**
     * docs/30 §9 L5: steps [seq] for all but its last frame, then draws and captures the last one, so the
     * picture (which depends on the layer's history) is the same every run.
     */
    fun captureAfter(seq: List<Features>): Frame {
        val target = source.updates + seq.size - 1
        source.playSequence(seq, seq.size - 1)
        await("${seq.size - 1} updates", 120_000) { source.updates >= target }
        waitIdle()
        return stepAndCapture()
    }

    /** One more update, drawn and captured in the same frame (the capture is armed before the update is let through). */
    fun stepAndCapture(): Frame {
        val latch = CountDownLatch(1)
        val out = AtomicReference<Frame>()
        val e = checkNotNull(engine())
        e.capture { buf: ByteBuffer, w: Int, h: Int ->
            out.set(Frame.fromGl(buf, w, h))
            latch.countDown()
        }
        source.allow(1)
        check(latch.await(30, TimeUnit.SECONDS)) { "no frame captured" }
        return out.get()
    }

    fun waitIdle() {
        var last = -1L
        var still = 0
        await("engine idle", 10_000) {
            val now = VizDebug.framesRendered.get()
            still = if (now == last) still + 1 else 0
            last = now
            still >= 3
        }
    }

    override fun close() {
        scenario.close()
        await("engine stopped") { VizDebug.liveThreads.get() == 0 }
        LabActivity.Harness.reset()
    }
}

fun waitFor(timeoutMs: Long, pollMs: Long = 100, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(pollMs)
    }
    return cond()
}

/** [waitFor] that fails the test on timeout instead of carrying on. */
fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) = check(waitFor(timeoutMs, cond = cond)) { "timed out waiting for $what" }

/** Runs a shell command and waits for it to finish. */
fun shell(cmd: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes()
    }
}
