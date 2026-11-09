package tv.jellybeam.visualizer.dsp

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import tv.jellybeam.visualizer.Features

/** Synthetic signals and a frame feeder; everything is generated in code from seeds. */
internal object Signals {
    const val FPS = 60

    fun sine(freq: Double, sr: Int, n: Int, amp: Double = 1.0): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (amp * sin(2.0 * PI * freq * i / sr)).toFloat()
        return out
    }

    /** Log sweep f0 to f1 over [n] samples (phase is the closed-form integral of the frequency). */
    fun logSweep(f0: Double, f1: Double, sr: Int, n: Int): FloatArray {
        val out = FloatArray(n)
        val total = n.toDouble() / sr
        val k = (f1 / f0)
        val lnK = ln(k)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val phase = 2.0 * PI * f0 * total / lnK * (k.pow(t / total) - 1.0)
            out[i] = sin(phase).toFloat()
        }
        return out
    }

    /** Paul Kellet's refined pink filter over seeded uniform white noise, scaled to the given rms. */
    fun pink(n: Int, seed: Long, rms: Double): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(n)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        var sq = 0.0
        for (i in 0 until n) {
            val w = rnd.nextDouble() * 2.0 - 1.0
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            out[i] = p.toFloat()
            sq += p * p
        }
        val scale = (rms / sqrt(sq / n)).toFloat()
        for (i in 0 until n) out[i] *= scale
        return out
    }

    /** A decaying 55 Hz sine burst starting at sample [t0]. */
    fun addKick(sig: FloatArray, t0: Int, sr: Int, amp: Double) {
        val tau = 0.05 * sr
        val len = (0.3 * sr).toInt()
        var n = 0
        while (n < len && t0 + n < sig.size) {
            sig[t0 + n] += (amp * exp(-n / tau) * sin(2.0 * PI * 55.0 * n / sr)).toFloat()
            n++
        }
    }

    /** Seeded white noise through two RBJ highpass biquads at [lo] and two lowpass at [hi] (24 dB/oct skirts), rms 1. */
    fun bandNoise(n: Int, seed: Long, lo: Double, hi: Double, sr: Int): FloatArray {
        val rnd = Random(seed)
        val warm = 256
        val x = DoubleArray(n + warm) { rnd.nextDouble() * 2.0 - 1.0 }
        biquad(x, lo, sr, highpass = true)
        biquad(x, lo, sr, highpass = true)
        biquad(x, hi, sr, highpass = false)
        biquad(x, hi, sr, highpass = false)
        var sq = 0.0
        for (i in warm until n + warm) sq += x[i] * x[i]
        val scale = 1.0 / sqrt(sq / n)
        return FloatArray(n) { (x[it + warm] * scale).toFloat() }
    }

    private fun biquad(x: DoubleArray, f: Double, sr: Int, highpass: Boolean) {
        val w0 = 2.0 * PI * f / sr
        val alpha = sin(w0) / (2.0 * 0.7071)
        val c = kotlin.math.cos(w0)
        val a0 = 1.0 + alpha
        val b0 = (if (highpass) (1.0 + c) / 2.0 else (1.0 - c) / 2.0) / a0
        val b1 = (if (highpass) -(1.0 + c) else 1.0 - c) / a0
        val b2 = b0
        val a1 = -2.0 * c / a0
        val a2 = (1.0 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val y = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y
            x[i] = y
        }
    }

    /** A snare from sample [t0]: noise bandpassed 200 Hz to 2 kHz (40 ms decay) plus a 180 Hz body (30 ms decay), 150 ms long. */
    fun addSnare(sig: FloatArray, t0: Int, sr: Int, amp: Double, seed: Long) {
        val len = (0.15 * sr).toInt()
        val noise = bandNoise(len, seed, 200.0, 2000.0, sr)
        var n = 0
        while (n < len && t0 + n < sig.size) {
            val body = exp(-n / (0.03 * sr)) * sin(2.0 * PI * 180.0 * n / sr)
            sig[t0 + n] += (amp * 0.7 * (noise[n] * exp(-n / (0.04 * sr)) + body)).toFloat()
            n++
        }
    }

    /** A hi-hat from sample [t0]: noise bandpassed 8 to 14 kHz with an 8 ms decay, 30 ms long. */
    fun addHat(sig: FloatArray, t0: Int, sr: Int, amp: Double, seed: Long) {
        val len = (0.03 * sr).toInt()
        val noise = bandNoise(len, seed, minOf(8000.0, 0.3 * sr), minOf(14000.0, 0.45 * sr), sr)
        var n = 0
        while (n < len && t0 + n < sig.size) {
            sig[t0 + n] += (amp * noise[n] * exp(-n / (0.008 * sr))).toFloat()
            n++
        }
    }

    /** Onset sample positions of a [drums] pattern; [hatsOnly] are the off-beat eighths with no kick or snare. */
    class Drums(
        val signal: FloatArray, val kicks: IntArray, val snares: IntArray, val hats: IntArray, val hatsOnly: IntArray,
        val beats: IntArray,
    )

    /** Kick on beats 1 and 3, snare on 2 and 4, hats on every eighth, from sample [start], over pink noise at [noiseRms]. */
    fun drums(
        sr: Int, seconds: Double, bpm: Double, noiseRms: Double, kick: Double, snare: Double, hat: Double,
        seed: Long = 11L, start: Int = 1300,
    ): Drums {
        val n = (seconds * sr).toInt()
        val sig = if (noiseRms > 0.0) pink(n, seed, noiseRms) else FloatArray(n)
        val half = sr * 30.0 / bpm
        val kicks = ArrayList<Int>()
        val snares = ArrayList<Int>()
        val hats = ArrayList<Int>()
        val hatsOnly = ArrayList<Int>()
        val beats = ArrayList<Int>()
        var i = 0
        while (true) {
            val t = (start + i * half).roundToInt()
            if (t >= n - 0.4 * sr) break
            if (hat > 0.0) addHat(sig, t, sr, hat, seed * 1000 + i)
            hats.add(t)
            if (i % 2 == 0) {
                beats.add(t)
                if ((i / 2) % 2 == 0) {
                    if (kick > 0.0) addKick(sig, t, sr, kick)
                    kicks.add(t)
                } else {
                    if (snare > 0.0) addSnare(sig, t, sr, snare, seed * 7000 + i)
                    snares.add(t)
                }
            } else {
                hatsOnly.add(t)
            }
            i++
        }
        return Drums(sig, kicks.toIntArray(), snares.toIntArray(), hats.toIntArray(), hatsOnly.toIntArray(), beats.toIntArray())
    }

    /** The frame whose window is centred on sample [t0] (docs/30 §2 sync). */
    fun frameOf(t0: Int, hop: Int): Int = ((t0 + 512).toDouble() / hop).roundToInt() - 1

    /** Index of the band containing [freq] under the 64-band 30 Hz to 16 kHz log layout. */
    fun bandOf(freq: Double): Int = (Features.BANDS * ln(freq / 30.0) / ln(16000.0 / 30.0)).toInt()

    fun bandCentre(i: Int): Double = 30.0 * (16000.0 / 30.0).pow((i + 0.5) / Features.BANDS)

    fun argMax(a: FloatArray): Int {
        var best = 0
        for (i in a.indices) if (a[i] > a[best]) best = i
        return best
    }

    // The module compiles against android.jar, which lacks java.lang.management; the host JVM has it at run time.
    private val bean: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    private val allocated = Class.forName("com.sun.management.ThreadMXBean")
        .getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)

    fun allocatedBytes(): Long = allocated.invoke(bean, Thread.currentThread().id) as Long

    /** Bytes the probe itself allocates between two back-to-back reads, after warm-up. */
    fun allocationOverhead(): Long {
        repeat(2000) { allocatedBytes() }
        val a = allocatedBytes()
        return allocatedBytes() - a
    }

    fun assertFinite(f: Features) {
        val all = arrayOf(f.bands, f.peaks, f.wave, f.waveL, f.waveR, f.beatAges)
        for (a in all) for (v in a) org.junit.Assert.assertTrue("non-finite $v", v.isFinite())
        val scalars = floatArrayOf(
            f.beatPulse, f.level, f.bass, f.mid, f.treble, f.timeS, f.kickPulse, f.snarePulse, f.hatPulse, f.flux,
            f.loudShort, f.loudLong, f.tempoBpm, f.beatPhase,
        )
        for (v in scalars) org.junit.Assert.assertTrue("non-finite $v", v.isFinite())
    }
}

/** Runs an [Analyzer] over a signal at [fps]: each frame feeds its new samples, then analyses the window ending at (f+1)*hop. */
internal class Feed(sr: Int, private val signal: FloatArray, fps: Int = Signals.FPS) {
    val analyzer = Analyzer(sr)
    val out = Features()
    val hop = sr / fps
    val dt = 1f / fps
    val frames = signal.size / hop
    private val mono = FloatArray(Analyzer.FFT_SIZE)
    private val left = FloatArray(Features.WAVE)
    private val right = FloatArray(Features.WAVE)

    /** Feeds the samples played since frame f-1, then analyses the window ending at frame f. */
    fun step(f: Int) = jump(f, 1)

    /** Frames [f] - [frames] + 1 .. [f] arrive in one call, as after a render stall. */
    fun jump(f: Int, frames: Int) {
        val end = (f + 1) * hop
        val start = maxOf(0, end - frames * hop)
        analyzer.feed(signal, start, minOf(end, signal.size) - start)
        for (i in 0 until Analyzer.FFT_SIZE) {
            val j = end - Analyzer.FFT_SIZE + i
            mono[i] = if (j >= 0 && j < signal.size) signal[j] else 0f
        }
        mono.copyInto(left, 0, Analyzer.FFT_SIZE - Features.WAVE, Analyzer.FFT_SIZE)
        left.copyInto(right)
        analyzer.analyze(mono, left, right, dt * frames, out)
    }

    /** The last frame again with no time passing, as a renderer does when the audio clock hasn't moved. */
    fun repeat() = analyzer.analyze(mono, left, right, 0f, out)
}
