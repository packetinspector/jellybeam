package tv.jellybeam.visualizer.lab

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import tv.jellybeam.visualizer.Features
import tv.jellybeam.visualizer.dsp.Analyzer

/** docs/30 §9: the four fixed signals the goldens and invariants draw, analysed by the real analyser. */
enum class Preset { SILENCE, TONE, KICK, NOISE }

object LabSignals {
    const val RATE = 48_000
    private const val HOP = RATE / 60

    /** The analyser's output for each of [frames] 60 fps frames of [preset]; deterministic. */
    fun sequence(preset: Preset, frames: Int = 180, kickHz: Double = 2.0): List<Features> {
        val a = Analyzer(RATE)
        val total = Analyzer.FFT_SIZE + HOP * frames
        val signal = FloatArray(total)
        val rnd = Random(7)
        val kickEvery = (RATE / kickHz).toInt()
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            signal[i] = when (preset) {
                Preset.SILENCE -> 0f
                Preset.TONE -> (0.5 * sin(2 * PI * 1000.0 * t)).toFloat()
                Preset.KICK -> {
                    val since = i % kickEvery
                    val kick = 0.9 * exp(-since / (RATE * 0.08)) * sin(2 * PI * 55.0 * since / RATE)
                    (kick + rnd.nextGaussian() * 0.02).toFloat()
                }
                Preset.NOISE -> (rnd.nextGaussian() * 0.25).toFloat().coerceIn(-1f, 1f)
            }
        }
        val mono = FloatArray(Analyzer.FFT_SIZE)
        val l = FloatArray(Features.WAVE)
        val r = FloatArray(Features.WAVE)
        val out = ArrayList<Features>(frames)
        val f = Features()
        for (k in 0 until frames) {
            val end = Analyzer.FFT_SIZE + HOP * (k + 1)
            System.arraycopy(signal, end - Analyzer.FFT_SIZE, mono, 0, Analyzer.FFT_SIZE)
            System.arraycopy(signal, end - Features.WAVE, l, 0, Features.WAVE)
            if (preset == Preset.TONE) {
                // A quarter-period lag on the right channel draws a circle on an XY display.
                for (j in 0 until Features.WAVE) r[j] = (0.5 * sin(2 * PI * 1000.0 * (end - Features.WAVE + j - 12).toDouble() / RATE)).toFloat()
            } else {
                System.arraycopy(l, 0, r, 0, Features.WAVE)
            }
            a.feed(signal, end - HOP, HOP)
            a.analyze(mono, l, r, 1f / 60f, f)
            out += Features().also { it.copyFrom(f) }
        }
        return out
    }

    /** [loudFrames] of noise then [silentFrames] of silence, analysed as one signal (docs/30 §9 L2). */
    fun loudThenSilent(loudFrames: Int, silentFrames: Int): List<Features> {
        val a = Analyzer(RATE)
        val total = Analyzer.FFT_SIZE + HOP * (loudFrames + silentFrames)
        val rnd = Random(7)
        val loudEnd = Analyzer.FFT_SIZE + HOP * loudFrames
        val signal = FloatArray(total) { i -> if (i < loudEnd) (rnd.nextGaussian() * 0.25).toFloat().coerceIn(-1f, 1f) else 0f }
        val mono = FloatArray(Analyzer.FFT_SIZE)
        val l = FloatArray(Features.WAVE)
        val f = Features()
        val out = ArrayList<Features>(loudFrames + silentFrames)
        for (k in 0 until loudFrames + silentFrames) {
            val end = Analyzer.FFT_SIZE + HOP * (k + 1)
            System.arraycopy(signal, end - Analyzer.FFT_SIZE, mono, 0, Analyzer.FFT_SIZE)
            System.arraycopy(signal, end - Features.WAVE, l, 0, Features.WAVE)
            a.feed(signal, end - HOP, HOP)
            a.analyze(mono, l, l, 1f / 60f, f)
            out += Features().also { it.copyFrom(f) }
        }
        return out
    }

    /**
     * The flash stress signal (docs/30 §9 L7): at [hz], a kick plus loud noise for half of each period and
     * silence for the rest, so the kick strobe and loudness-driven brightness both swing as hard as they can.
     */
    fun strobe(frames: Int, hz: Double): List<Features> {
        val a = Analyzer(RATE)
        val total = Analyzer.FFT_SIZE + HOP * frames
        val rnd = Random(7)
        val every = (RATE / hz).toInt()
        val signal = FloatArray(total) { i ->
            val since = i % every
            if (since >= every / 2) 0f else {
                val kick = 0.6 * exp(-since / (RATE * 0.08)) * sin(2 * PI * 55.0 * since / RATE)
                (kick + rnd.nextGaussian() * 0.25).toFloat().coerceIn(-1f, 1f)
            }
        }
        val mono = FloatArray(Analyzer.FFT_SIZE)
        val l = FloatArray(Features.WAVE)
        val f = Features()
        val out = ArrayList<Features>(frames)
        for (k in 0 until frames) {
            val end = Analyzer.FFT_SIZE + HOP * (k + 1)
            System.arraycopy(signal, end - Analyzer.FFT_SIZE, mono, 0, Analyzer.FFT_SIZE)
            System.arraycopy(signal, end - Features.WAVE, l, 0, Features.WAVE)
            a.feed(signal, end - HOP, HOP)
            a.analyze(mono, l, l, 1f / 60f, f)
            out += Features().also { it.copyFrom(f) }
        }
        return out
    }

    /** The last frame of [sequence]: a settled picture of [preset]. */
    fun features(preset: Preset, frames: Int = 30): Features = sequence(preset, frames).last()
}
