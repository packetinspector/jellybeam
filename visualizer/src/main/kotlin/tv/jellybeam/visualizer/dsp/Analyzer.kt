package tv.jellybeam.visualizer.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import tv.jellybeam.visualizer.Features

/**
 * docs/30 §3: turns a 1024-sample mono window into [Features]. All state and scratch are preallocated,
 * so [analyze] allocates nothing.
 */
class Analyzer(sampleRate: Int) {
    var sampleRate: Int = sampleRate
        private set

    private val fft = RealFft(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE)
    private val frame = FloatArray(FFT_SIZE)
    private val re = FloatArray(HALF + 1)
    private val im = FloatArray(HALF + 1)
    private val mag = FloatArray(HALF + 3) // the last two stay 0: reads past Nyquist are silence
    private val interp = BooleanArray(Features.BANDS)
    private val bandLo = IntArray(Features.BANDS)
    private val bandHi = IntArray(Features.BANDS)
    private val bandFrac = FloatArray(Features.BANDS)
    private val smooth = FloatArray(Features.BANDS)
    private val peak = FloatArray(Features.BANDS)
    private val hold = FloatArray(Features.BANDS)
    private val ages = FloatArray(Features.BEAT_SLOTS) { Features.NO_BEAT }
    private val magScale: Float
    private val windowSum: Float
    private var pulse = 0f

    /** Double so motion keeps moving over days of ambient play; a Float clock stalls after about three. */
    private var time = 0.0

    private val hops = HopAnalyzer(sampleRate)
    private var kickPulse = 0f
    private var snarePulse = 0f
    private var hatPulse = 0f
    private var flux = 0f
    private var msShort = 0f
    private var msLong = 0f

    init {
        requireRate(sampleRate)
        var sum = 0.0
        for (i in 0 until FFT_SIZE) {
            window[i] = (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / FFT_SIZE)).toFloat()
            sum += window[i]
        }
        windowSum = sum.toFloat()
        magScale = (2.0 / sum).toFloat() // a full-scale sine reads 1.0, which is 0 dB
        mapBands()
    }

    /** New sample rate: remaps the bands and clears the dynamics; [Features.timeS] keeps running. */
    fun reconfigure(sampleRate: Int) {
        requireRate(sampleRate)
        this.sampleRate = sampleRate
        mapBands()
        hops.reconfigure(sampleRate)
        reset()
    }

    /** Clears release, peak, onset, tempo and age state, e.g. after a seek. */
    fun reset() {
        smooth.fill(0f)
        peak.fill(0f)
        hold.fill(0f)
        ages.fill(Features.NO_BEAT)
        pulse = 0f
        hops.reset()
        kickPulse = 0f
        snarePulse = 0f
        hatPulse = 0f
        flux = 0f
        msShort = 0f
        msLong = 0f
    }

    /**
     * docs/30 §3 (E5): the next [count] played mono samples from [offset], oldest first, everything since the
     * previous call; onsets and tempo run on these, and [analyze] publishes them.
     */
    fun feed(mono: FloatArray, offset: Int, count: Int) = hops.feed(mono, offset, count)

    /**
     * [mono] is the newest [FFT_SIZE] samples, newest last; [left] and [right] the newest [Features.WAVE].
     * [dtS] is the audio time since the previous call. Onset and tempo fields publish what [feed] found since
     * the previous call with time passing.
     */
    fun analyze(mono: FloatArray, left: FloatArray, right: FloatArray, dtS: Float, out: Features) {
        val dt = if (dtS > 0f) minOf(dtS, MAX_DT_S) else 0f
        var weighted = 0f
        var sq = 0f
        for (i in 0 until FFT_SIZE) {
            val s = mono[i]
            weighted += window[i] * s
            sq += s * s
        }
        // The window-weighted mean makes the windowed frame's DC exactly zero; a plain mean leaks into bin 1.
        val mean = weighted / windowSum
        for (i in 0 until FFT_SIZE) frame[i] = (mono[i] - mean) * window[i]
        fft.transform(frame, re, im)
        for (k in 0..HALF) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * magScale

        for (b in 0 until Features.BANDS) {
            val lo = bandLo[b]
            var m: Float
            if (interp[b]) {
                m = mag[lo] * (1f - bandFrac[b]) + mag[lo + 1] * bandFrac[b]
            } else {
                m = 0f
                for (k in lo..bandHi[b]) if (mag[k] > m) m = mag[k]
            }
            val v = (20f * log10(if (m > 1e-9f) m else 1e-9f) + DB_RANGE) / DB_RANGE
            val band = if (v > 1f) 1f else if (v > 0f) v else 0f // NaN lands on 0

            var s = smooth[b]
            s = if (band >= s) band else maxOf(band, s - RELEASE_PER_S * dt)
            smooth[b] = s

            if (band >= peak[b]) {
                peak[b] = band
                hold[b] = PEAK_HOLD_S
            } else if (hold[b] > 0f) {
                hold[b] -= dt
            } else {
                peak[b] = maxOf(band, peak[b] - PEAK_FALL_PER_S * dt)
            }
        }

        val rms = sqrt(sq / FFT_SIZE)
        val level = if (rms > 1f) 1f else if (rms > 0f) rms else 0f
        // A repeated frame (dt 0) publishes nothing new, so no onset or tick repeats.
        val stepped = dt > 0f
        val kick = stepped && hops.kick
        val snare = stepped && hops.snare
        val hat = stepped && hops.hat
        val tick = stepped && hops.tick && hops.count > 0
        val phraseTick = tick && hops.phraseTick
        if (stepped) {
            for (i in 0 until Features.BEAT_SLOTS) if (ages[i] < Features.NO_BEAT) ages[i] += dt
            if (kick) {
                for (i in Features.BEAT_SLOTS - 1 downTo 1) ages[i] = ages[i - 1]
                ages[0] = 0f
                pulse = 1f
            } else {
                pulse = maxOf(0f, pulse - dt / PULSE_S)
            }
            kickPulse = if (kick) 1f else maxOf(0f, kickPulse - dt / CLASS_PULSE_S)
            snarePulse = if (snare) 1f else maxOf(0f, snarePulse - dt / CLASS_PULSE_S)
            hatPulse = if (hat) 1f else maxOf(0f, hatPulse - dt / CLASS_PULSE_S)
            if (hops.hops > 0) flux = hops.flux
            msShort += dt / (LOUD_SHORT_S + dt) * (level * level - msShort)
            msLong += dt / (LOUD_LONG_S + dt) * (level * level - msLong)
            time += dt.toDouble()
            hops.clearInterval()
        }

        smooth.copyInto(out.bands)
        peak.copyInto(out.peaks)
        ages.copyInto(out.beatAges)
        mono.copyInto(out.wave, 0, FFT_SIZE - Features.WAVE, FFT_SIZE)
        left.copyInto(out.waveL, 0, 0, Features.WAVE)
        right.copyInto(out.waveR, 0, 0, Features.WAVE)
        out.beat = kick
        out.beatPulse = pulse
        out.level = level
        out.bass = mean(smooth, 0, BASS_END)
        out.mid = mean(smooth, BASS_END, MID_END)
        out.treble = mean(smooth, MID_END, Features.BANDS)
        out.timeS = time.toFloat()
        out.kick = kick
        out.snare = snare
        out.hat = hat
        out.kickPulse = kickPulse
        out.snarePulse = snarePulse
        out.hatPulse = hatPulse
        out.flux = flux
        out.loudShort = minOf(sqrt(msShort), 1f)
        out.loudLong = minOf(sqrt(msLong), 1f)
        out.tempoLocked = hops.locked
        out.tempoBpm = hops.bpm
        out.beatPhase = hops.phase
        out.beatTick = tick
        out.beatCount = hops.count
        out.phrase = hops.count / HopAnalyzer.PHRASE_BEATS
        out.phraseTick = phraseTick
        out.silent = hops.silent
    }


    /** docs/30 §3: class bands assume 8–96 kHz; the caller decimates higher rates first. */
    private fun requireRate(rate: Int) = require(rate in MIN_RATE..MAX_RATE) { "sample rate $rate" }


    private fun mean(a: FloatArray, from: Int, to: Int): Float {
        var s = 0f
        for (i in from until to) s += a[i]
        return s / (to - from)
    }

    private fun mapBands() {
        val binHz = sampleRate.toDouble() / FFT_SIZE
        val ratio = F_MAX / F_MIN
        for (b in 0 until Features.BANDS) {
            val lo = F_MIN * ratio.pow(b.toDouble() / Features.BANDS)
            val hi = F_MIN * ratio.pow((b + 1.0) / Features.BANDS)
            if (hi - lo < INTERP_BELOW_BINS * binHz) {
                val pos = sqrt(lo * hi) / binHz
                val i0 = minOf(floor(pos).toInt(), HALF + 1)
                interp[b] = true
                bandLo[b] = i0
                bandFrac[b] = (pos - i0).toFloat()
            } else {
                interp[b] = false
                bandLo[b] = minOf(ceil(lo / binHz).toInt(), HALF + 1)
                bandHi[b] = minOf(ceil(hi / binHz).toInt() - 1, HALF)
            }
        }
    }


    companion object {
        const val FFT_SIZE = 1024
        const val MIN_RATE = 8000
        const val MAX_RATE = 96000

        /** A longer gap (a stall, a pause the caller missed) counts as this much, so no state jumps to infinity. */
        private const val MAX_DT_S = 0.25f
        private const val HALF = FFT_SIZE / 2
        private const val F_MIN = 30.0
        private const val F_MAX = 16000.0
        private const val DB_RANGE = 70f
        private const val RELEASE_PER_S = 1.2f
        private const val PEAK_HOLD_S = 0.5f
        private const val PEAK_FALL_PER_S = 1.5f
        private const val BASS_END = 10
        private const val MID_END = 40
        private const val PULSE_S = 0.4f

        /** Bands under this many bins wide read the spectrum at their centre instead of the max bin. */
        private const val INTERP_BELOW_BINS = 2.0

        private const val CLASS_PULSE_S = 0.25f
        private const val LOUD_SHORT_S = 0.1f
        private const val LOUD_LONG_S = 3f
    }
}
