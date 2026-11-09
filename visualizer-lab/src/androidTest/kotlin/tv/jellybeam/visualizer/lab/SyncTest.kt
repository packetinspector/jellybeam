package tv.jellybeam.visualizer.lab

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.gl.VizDebug
import tv.jellybeam.visualizer.tap.PcmTap
import tv.jellybeam.visualizer.tap.SampleRing
import kotlin.math.abs

/** docs/30 §9 T25–T27: the tap behind a real ExoPlayer: window sync, gapless continuity, zero idle cost. */
@RunWith(AndroidJUnit4::class)
class SyncTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val rate = LabSignals.RATE

    private companion object {
        const val SEEK_MS = 31_250L
    }

    private fun <T> main(block: () -> T): T {
        var out: T? = null
        inst.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun startPlayer(tap: PcmTap, vararg files: File): ExoPlayer = main {
        LabPlayer.build(ctx, tap).also { p ->
            p.setMediaItems(files.map(LabPlayer::item))
            p.prepare()
            p.play()
        }
    }

    /** Onsets (first sample above half scale after one below) in [mono], as indices. */
    private fun onsets(mono: FloatArray): List<Int> =
        (1 until mono.size).filter { mono[it] > 0.5f && mono[it - 1] <= 0.5f }

    /**
     * The click heard now must sit at the window's centre. The reference is the output's own reported
     * position (Media3's sink clock) plus the media time it started at; the player's main-thread
     * position is quantised on the emulator and can't serve. Clicks are at every 500 ms of media.
     */
    @Test
    fun windowCentredOnTheOutputPositionMatchesThePlayerAcrossPauseAndSeek() {
        val tap = PcmTap()
        val ring = SampleRing()
        tap.attach(ring)
        val wav = LabPlayer.clickWav(File(ctx.cacheDir, "clicks-sync.wav"), 60)
        val player = startPlayer(tap, wav)
        val mono = FloatArray(1024)
        val l = FloatArray(Features.WAVE)
        val r = FloatArray(Features.WAVE)
        // One error per distinct click (media time in ms), so a click seen by several polls counts once.
        val errors = linkedMapOf<Long, Double>()
        val start = System.currentTimeMillis()
        var startPtsUs = 0L
        var pausedFrozen = false
        var seeked = false
        try {
            check(waitFor(10_000) { main { player.playbackState == Player.STATE_READY && player.isPlaying } && LabPlayer.lastPositionNanos != 0L })
            // Renderer timestamps carry a stream offset; media time is the PTS relative to the first one.
            startPtsUs = LabPlayer.firstPtsUs
            while ((errors.size < 60 || !seeked) && System.currentTimeMillis() - start < 120_000) {
                if (!pausedFrozen && errors.size >= 15) {
                    // Pause: the clock stops within a report and the window stays put.
                    main { player.pause() }
                    check(waitFor(1_000, pollMs = 10) { !tap.isRunning }) { "tap still running after pause" }
                    // The output may report its last drained frames just after the pause; then it holds.
                    Thread.sleep(300)
                    val a = tap.position(System.nanoTime())
                    Thread.sleep(700)
                    val b = tap.position(System.nanoTime())
                    assertEquals("paused window moved", a, b, 1.0)
                    main { player.play() }
                    check(waitFor(2_000, pollMs = 10) { tap.isRunning })
                    pausedFrozen = true
                }
                if (pausedFrozen && !seeked && errors.size >= 30) {
                    main { player.seekTo(SEEK_MS) }
                    seeked = true
                    // The seek lands on the extractor's grid, so the base is what was actually written first.
                    check(waitFor(3_000, pollMs = 10) { LabPlayer.firstPtsUs != Long.MIN_VALUE && LabPlayer.firstPtsUs != startPtsUs })
                    Thread.sleep(1_000)
                }
                // Tap and reference from the same position report: retry if a new one lands in between.
                var nanos: Long
                var frame: Double
                var outMs: Double
                do {
                    val report = LabPlayer.lastPositionNanos
                    nanos = System.nanoTime()
                    frame = tap.position(nanos)
                    outMs = LabPlayer.lastPositionUs / 1000.0 + if (tap.isRunning) (nanos - report) / 1e6 else 0.0
                } while (LabPlayer.lastPositionNanos != report)
                val nowMs = (LabPlayer.firstPtsUs - startPtsUs) / 1000.0 + outMs
                ring.read(frame.toLong(), mono, l, r)
                for (i in onsets(mono)) {
                    val offsetMs = (i - mono.size / 2) * 1000.0 / rate
                    val clickMs = Math.round((nowMs + offsetMs) / 500.0) * 500L
                    errors.getOrPut(clickMs) { (nowMs + offsetMs) - clickMs }
                }
                Thread.sleep(7)
            }
        } finally {
            main { player.release() }
            tap.detach(ring)
        }
        assertTrue("pause and seek both exercised", pausedFrozen && seeked)
        assertTrue("a click after the seek", errors.keys.any { it >= SEEK_MS })
        Log.i("VizSync", "n=${errors.size} max=${errors.values.maxOfOrNull { abs(it) }} mean=${errors.values.average()}")
        assertTrue("only ${errors.size} distinct clicks seen", errors.size >= 60)
        val worst = errors.values.maxOf { abs(it) }
        assertTrue("worst window/output disagreement $worst ms", worst <= 16.7)
    }

    @Test
    fun gaplessJoinKeepsTheClickSpacingInTheTap() {
        val tap = PcmTap()
        val ring = SampleRing()
        tap.attach(ring)
        val a = LabPlayer.clickWav(File(ctx.cacheDir, "clicks-a.wav"), 6)
        val b = LabPlayer.clickWav(File(ctx.cacheDir, "clicks-b.wav"), 6)
        val player = startPlayer(tap, a, b)
        val mono = FloatArray(16_384)
        val l = FloatArray(Features.WAVE)
        val r = FloatArray(Features.WAVE)
        val clicks = sortedSetOf<Long>()
        try {
            check(waitFor(10_000) { main { player.isPlaying } })
            val end = System.currentTimeMillis() + 13_500
            while (System.currentTimeMillis() < end) {
                val frame = tap.position(System.nanoTime()).toLong()
                ring.read(frame, mono, l, r)
                for (i in onsets(mono)) clicks += frame - mono.size / 2 + i
                Thread.sleep(50)
            }
        } finally {
            main { player.release() }
            tap.detach(ring)
        }
        val list = clicks.toList()
        Log.i("VizSync", "gapless clicks=${list.size}")
        assertTrue("only ${list.size} clicks", list.size >= 22)
        assertTrue("the track boundary at 6 s was seen", list.any { it == 6L * rate })
        val period = rate / 2
        for (k in 1 until list.size) {
            assertEquals("spacing before click $k", period.toLong(), list[k] - list[k - 1])
        }
    }

    @Test
    fun neverOpenedCostsNothing() {
        val tap = PcmTap()
        val wav = LabPlayer.clickWav(File(ctx.cacheDir, "clicks-idle.wav"), 60)
        val seconds = InstrumentationRegistry.getArguments().getString("idleSeconds")?.toLong() ?: 60L
        val player = startPlayer(tap, wav)
        try {
            Thread.sleep(seconds * 1000)
            assertTrue(main { player.isPlaying || player.playbackState == Player.STATE_ENDED })
        } finally {
            main { player.release() }
        }
        assertFalse(tap.isAttached)
        assertEquals(0L, tap.offeredToRing)
        assertEquals(0, VizDebug.liveThreads.get())
        assertEquals(0, VizDebug.liveContexts.get())
    }
}
