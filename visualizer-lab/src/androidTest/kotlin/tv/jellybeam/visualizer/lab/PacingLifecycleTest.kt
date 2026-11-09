package tv.jellybeam.visualizer.lab

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.jellybeam.visualizer.gl.VizDebug

/** docs/30 §9 T22, T23: idle when settled, prompt on resume, halving after misses; no leaks over many opens. */
@RunWith(AndroidJUnit4::class)
class PacingLifecycleTest {
    @After
    fun resetHooks() {
        VizDebug.slowFrameHook = false
        LabActivity.Harness.reset()
        shell("input keyevent KEYCODE_WAKEUP")
    }

    @Test
    fun pausedAndSettledRendersNothing() {
        Lab().use { lab ->
            lab.settle(Preset.NOISE)
            val before = VizDebug.framesRendered.get()
            Thread.sleep(5_000)
            assertEquals(before, VizDebug.framesRendered.get())
        }
    }

    @Test
    fun resumeDrawsWithinAVsync() {
        Lab().use { lab ->
            lab.settle(Preset.NOISE)
            VizDebug.wakeLatencyNanos = -1L
            lab.source.play(LabSignals.features(Preset.KICK), -1)
            waitFor(2_000, pollMs = 5) { VizDebug.wakeLatencyNanos >= 0 }
            lab.source.stop()
            val ms = VizDebug.wakeLatencyNanos / 1e6
            // D7: the next vsync serves it; one period plus the hop to the GL thread.
            assertTrue("vsync callback ${ms} ms after the wake", ms in 0.0..(16.7 + 4.0))
        }
    }

    @Test
    fun threeForcedMissesHalveTheRate() {
        Lab().use { lab ->
            lab.source.play(LabSignals.features(Preset.NOISE), -1)
            Thread.sleep(500)
            val b0 = VizDebug.framesRendered.get()
            Thread.sleep(2_000)
            val baseFps = (VizDebug.framesRendered.get() - b0) / 2.0
            VizDebug.slowFrameHook = true
            Thread.sleep(200)
            VizDebug.slowFrameHook = false
            assertTrue(lab.engine()!!.halved)
            val before = VizDebug.framesRendered.get()
            Thread.sleep(2_000)
            val fps = (VizDebug.framesRendered.get() - before) / 2.0
            lab.source.stop()
            // Never above half the refresh, and close to it unless the host can't draw that fast (swiftshader).
            assertTrue("fps $fps (base $baseFps)", fps in 0.8 * minOf(baseFps, 30.0)..34.0)
        }
    }

    /**
     * The same 50 opens of the same window with a plain view are the control: the emulator's platform
     * leaks native heap per window (and its guest GL driver per context), so the engine is judged on
     * what it adds beyond that.
     */
    @Test
    fun fiftyOpensLeakNoThreadContextOrNativeHeap() {
        val threads0 = VizDebug.liveThreads.get()
        val contexts0 = VizDebug.liveContexts.get()
        val opens = InstrumentationRegistry.getArguments().getString("leakOpens")?.toInt() ?: 50
        fun growth(open: () -> Unit): Long {
            repeat(10) { open() }
            System.gc()
            System.runFinalization()
            Thread.sleep(300)
            val heap0 = Debug.getNativeHeapAllocatedSize()
            repeat(opens) { open() }
            System.gc()
            System.runFinalization()
            Thread.sleep(300)
            return Debug.getNativeHeapAllocatedSize() - heap0
        }
        LabActivity.Harness.bare = true
        val plain = try {
            growth { androidx.test.core.app.ActivityScenario.launch(LabActivity::class.java).use { Thread.sleep(150) } }
        } finally {
            LabActivity.Harness.bare = false
        }
        val bytes0 = VizDebug.gpuBytes.get()
        var peakBytes = 0L
        val engine = growth {
            Lab().use {
                it.settle(Preset.NOISE, 5)
                peakBytes = maxOf(peakBytes, VizDebug.gpuBytes.get() - bytes0)
            }
        }
        android.util.Log.i("VizLeak", "gpu bytes per engine $peakBytes")
        // L3 (E1): everything one visualizer allocates on the GPU fits in 1 MB and is freed on close.
        assertTrue("gpu bytes $peakBytes", peakBytes in 1..(1L shl 20))
        assertEquals(bytes0, VizDebug.gpuBytes.get())
        android.util.Log.i("VizLeak", "native heap over $opens opens: engine=$engine plain=$plain extra=${engine - plain}")
        assertEquals(threads0, VizDebug.liveThreads.get())
        assertEquals(contexts0, VizDebug.liveContexts.get())
        assertTrue("engine added ${engine - plain} bytes over the plain window", engine - plain < 1_000_000)
    }

    @Test
    fun backHomeAndScreenOffDestroyTheSurface() {
        val threads0 = VizDebug.liveThreads.get()
        Lab().use {
            assertEquals(threads0 + 1, VizDebug.liveThreads.get())
            shell("input keyevent KEYCODE_BACK")
            assertTrue("Back left the engine running", waitFor(5_000) { VizDebug.liveThreads.get() == threads0 })
        }
        Lab().use {
            assertEquals(threads0 + 1, VizDebug.liveThreads.get())
            shell("input keyevent KEYCODE_HOME")
            assertTrue("Home left the engine running", waitFor(5_000) { VizDebug.liveThreads.get() == threads0 })
        }
        Lab().use {
            assertEquals(threads0 + 1, VizDebug.liveThreads.get())
            shell("input keyevent KEYCODE_SLEEP")
            val stopped = waitFor(5_000) { VizDebug.liveThreads.get() == threads0 }
            shell("input keyevent KEYCODE_WAKEUP")
            assertTrue("screen-off left the engine running", stopped)
        }
    }
}
