package tv.jellybeam.visualizer.lab

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import tv.jellybeam.visualizer.FeatureSource
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.dsp.Analyzer
import tv.jellybeam.visualizer.gl.VizDebug
import tv.jellybeam.visualizer.pattern.Pattern
import java.util.Random

/**
 * docs/30 §9 T24: CPU time per frame on the emulator (analysis and GL submission, p95 over up to 1800
 * frames per pattern, and during a crossfade), reported as a table and held to the L9 budgets.
 * GPU time is measured on the TV only.
 */
@RunWith(AndroidJUnit4::class)
class PerfTest {
    /** Runs the real analyser every vsync on noise generated up front, so only analysis is timed. */
    private class LiveNoise : FeatureSource {
        private val a = Analyzer(LabSignals.RATE)
        private val noise = Random(3).let { rnd -> FloatArray(LabSignals.RATE * 4) { (rnd.nextGaussian() * 0.2).toFloat() } }
        private var at = 0
        private val mono = FloatArray(Analyzer.FFT_SIZE)
        private val l = FloatArray(Features.WAVE)
        private val r = FloatArray(Features.WAVE)
        override val running = true
        override val encoded = false
        override fun open(wake: Runnable) = Unit
        override fun update(frameNanos: Long, out: Features): Boolean {
            at = (at + LabSignals.RATE / 60) % (noise.size - mono.size)
            System.arraycopy(noise, at, mono, 0, mono.size)
            a.feed(noise, at + mono.size - LabSignals.RATE / 60, LabSignals.RATE / 60)
            System.arraycopy(mono, mono.size - l.size, l, 0, l.size)
            System.arraycopy(l, 0, r, 0, r.size)
            a.analyze(mono, l, r, 1f / 60f, out)
            return true
        }
        override fun close() = Unit
    }

    @Test
    fun cpuTimePerFrame() {
        val frames = InstrumentationRegistry.getArguments().getString("perfFrames")?.toInt() ?: 1800
        val rows = StringBuilder("pattern | analyze p95 ms | submit p95 ms | stats wait p95 ms (GPU) | frames | fps | crossfade submit p95 ms\n")
        var worst = 0.0
        var worstSubmit = 0.0
        for (p in Pattern.entries) {
            LabActivity.Harness.pattern = p
            LabActivity.Harness.sourceFactory = { LiveNoise() }
            val want = minOf(frames, VizDebug.TIMING_FRAMES)
            var n = 0
            var an = emptyList<Long>()
            var sub = emptyList<Long>()
            var wait = emptyList<Long>()
            var fade = emptyList<Long>()
            var fps = 0.0
            androidx.test.core.app.ActivityScenario.launch(LabActivity::class.java).use { scenario ->
                val start = VizDebug.timingCount.get()
                val t0 = System.nanoTime()
                // swiftshader rasterises on the host CPU, so the frame count is capped by time; CPU cost per frame is what's judged.
                waitFor(MEASURE_MS) { VizDebug.timingCount.get() - start >= want }
                val end = VizDebug.timingCount.get()
                fps = (end - start) * 1e9 / (System.nanoTime() - t0)
                n = minOf(want.toLong(), end - start).toInt()
                check(n >= MIN_FRAMES) { "only $n timed frames in $MEASURE_MS ms" }
                val idx = (0 until n).map { ((end - 1 - it) % VizDebug.TIMING_FRAMES).toInt() }
                an = idx.map { VizDebug.analyzeNanos[it] }.sorted()
                sub = idx.map { VizDebug.submitNanos[it] }.sorted()
                wait = idx.map { VizDebug.statsWaitNanos[it] }.sorted()
                // L9: the same during a crossfade, when two patterns render at once (E6).
                val starts = VizDebug.autoStarts.get()
                scenario.onActivity { it.view.currentEngine?.forceTransition() }
                await("crossfade start", 10_000) { VizDebug.autoStarts.get() > starts }
                // Measured from the frame the crossfade started, so the window holds only two-pattern frames.
                val fadeStart = VizDebug.autoStartTiming
                await("crossfade frames", 60_000) { VizDebug.timingCount.get() - fadeStart >= FADE_FRAMES }
                val fadeEnd = fadeStart + FADE_FRAMES
                fade = (0 until FADE_FRAMES).map { VizDebug.submitNanos[((fadeEnd - 1 - it) % VizDebug.TIMING_FRAMES).toInt()] }.sorted()
                worstSubmit = maxOf(worstSubmit, p95(fade))
            }
            await("engine stopped") { VizDebug.liveThreads.get() == 0 }
            LabActivity.Harness.reset()
            worst = maxOf(worst, p95(an))
            worstSubmit = maxOf(worstSubmit, p95(sub))
            rows.append("${p.key} | ${"%.3f".format(p95(an))} | ${"%.3f".format(p95(sub))} | ${"%.3f".format(p95(wait))} | $n | ${"%.1f".format(fps)} | ${"%.3f".format(p95(fade))}\n")
        }
        Log.i("VizPerf", rows.toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("perf", rows.toString()) })
        // CPU budgets only (L9): the GPU time behind them is measured on the TV.
        assertTrue("analysis p95 $worst ms", worst <= ANALYZE_P95_MS)
        assertTrue("CPU submit p95 $worstSubmit ms", worstSubmit <= SUBMIT_P95_MS)
    }

    private fun p95(v: List<Long>) = if (v.isEmpty()) Double.NaN else v[(v.size * 95 / 100).coerceAtMost(v.size - 1)] / 1e6

    private companion object {
        const val ANALYZE_P95_MS = 0.3
        const val SUBMIT_P95_MS = 1.0
        const val FADE_FRAMES = 90
        const val MEASURE_MS = 60_000L
        const val MIN_FRAMES = 300
    }
}
