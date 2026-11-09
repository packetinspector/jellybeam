package tv.jellybeam.visualizer

import tv.jellybeam.visualizer.dsp.Analyzer
import tv.jellybeam.visualizer.tap.PcmTap
import tv.jellybeam.visualizer.tap.SampleRing
import tv.jellybeam.visualizer.tap.TapState

/** docs/30 §6: what the renderer draws from; called on the GL thread only, between [open] and [close]. */
interface FeatureSource {
    /** Starts producing; [wake] may be called from any thread when a paused source starts running again. */
    fun open(wake: Runnable)

    /** Fills [out] for the vsync at [frameNanos]; returns false when nothing changed since the last call. */
    fun update(frameNanos: Long, out: Features): Boolean

    /** True while the audio clock runs (more frames will change the picture). */
    val running: Boolean

    /** True when the audio can't be analysed (passthrough): D10's still fallback. */
    val encoded: Boolean

    fun close()
}

/**
 * docs/30 §2: the live source. Attaches a ring to the music output's [tap] only while open, reads the
 * window centred on the output position and analyses it; frozen (no analysis) while the clock stops.
 */
class TapFeatureSource(private val tap: PcmTap) : FeatureSource {
    private val ring = SampleRing()
    private val mono = FloatArray(Analyzer.FFT_SIZE)
    private val left = FloatArray(Features.WAVE)
    private val right = FloatArray(Features.WAVE)

    // docs/30 §3: high rates are averaged down to 44.1/48 kHz first, so the 1024 window keeps its 21 ms span.
    private val rawMono = FloatArray(Analyzer.FFT_SIZE * MAX_DECIMATION)
    private val rawLeft = FloatArray(Features.WAVE * MAX_DECIMATION)
    private val rawRight = FloatArray(Features.WAVE * MAX_DECIMATION)

    // docs/30 §3: every played sample reaches the onset analysis, whatever the frame rate.
    private val feedRaw = FloatArray(MAX_FEED * MAX_DECIMATION)
    private val feedOut = FloatArray(MAX_FEED)
    private var fedFrame = -1L
    private var analyzer: Analyzer? = null
    private var lastFrame = Double.NaN
    private var lastNanos = 0L

    // A fresh ring with no audio yet is not running, whatever the last position report said.
    override val running: Boolean get() = tap.isRunning && ring.sampleRate != 0

    override val encoded: Boolean get() = tap.state == TapState.ENCODED

    private var wake: Runnable? = null

    override fun open(wake: Runnable) {
        this.wake = wake
        tap.onResume = wake
        tap.attach(ring)
        lastFrame = Double.NaN
    }

    override fun update(frameNanos: Long, out: Features): Boolean {
        val rate = ring.sampleRate
        if (rate == 0) return false
        val k = decimation(rate)
        val analysisRate = analysisRate(rate) ?: return false
        val a = analyzer?.also { if (it.sampleRate != analysisRate) it.reconfigure(analysisRate) } ?: Analyzer(analysisRate).also { analyzer = it }
        val frame = tap.position(frameNanos)
        if (frame == lastFrame) return false
        feedPlayed(a, frame.toLong(), k)
        val dtS = if (lastNanos == 0L || lastFrame.isNaN()) 1f / 60f else ((frameNanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = frame
        lastNanos = frameNanos
        if (k == 1) {
            ring.read(frame.toLong(), mono, left, right)
        } else {
            ring.read(frame.toLong(), rawMono, rawLeft, rawRight, Analyzer.FFT_SIZE * k, Features.WAVE * k)
            average(rawMono, mono, k)
            average(rawLeft, left, k)
            average(rawRight, right, k)
        }
        a.analyze(mono, left, right, dtS, out)
        return true
    }

    /** Feeds output frames played since the last frame; a seek (backwards) or a long gap restarts the analysis. */
    private fun feedPlayed(a: Analyzer, now: Long, k: Int) {
        val maxRaw = MAX_FEED.toLong() * k
        if (fedFrame < 0 || now < fedFrame || now - fedFrame > maxRaw * 4) {
            if (fedFrame >= 0) a.reset()
            fedFrame = maxOf(0L, now - maxRaw)
        }
        // Whole analysis samples only; a long hitch keeps its newest quarter second.
        val raw = ((now - fedFrame) / k * k).coerceAtMost(maxRaw)
        if (raw <= 0) return
        val from = now - raw
        ring.readMono(from, feedRaw, raw.toInt())
        val n = (raw / k).toInt()
        if (k == 1) {
            a.feed(feedRaw, 0, n)
        } else {
            average(feedRaw, feedOut, k, n)
            a.feed(feedOut, 0, n)
        }
        fedFrame = from + raw
    }

    private fun average(src: FloatArray, out: FloatArray, k: Int, n: Int = out.size) {
        for (i in 0 until n) {
            var s = 0f
            for (j in 0 until k) s += src[i * k + j]
            out[i] = s / k
        }
    }

    override fun close() {
        fedFrame = -1L
        tap.detach(ring)
        if (tap.onResume === wake) tap.onResume = null
        analyzer?.reset()
    }
}

/** docs/30 §3: 88.2/96 kHz analyse at half rate, 176.4/192 kHz at a quarter; 44.1/48 kHz as they are. */
internal fun decimation(rate: Int): Int = ((rate + 30_000) / 48_000).coerceIn(1, MAX_DECIMATION)

internal const val MAX_DECIMATION = 4

/** docs/30 §3: the rate [rate] is analysed at, or null outside the analyser's range (e.g. 7.35 kHz): those draw no frames, never throw. */
internal fun analysisRate(rate: Int): Int? = (rate / decimation(rate)).takeIf { it in Analyzer.MIN_RATE..Analyzer.MAX_RATE }

/** A quarter second at 48 kHz: the most one frame feeds the onset analysis. */
private const val MAX_FEED = 12_000
