package tv.jellybeam.visualizer.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.sqrt

/**
 * docs/30 §3 (E5): onsets, flux, silence and tempo from the played samples themselves, every [HOP] samples
 * over the latest [Analyzer.FFT_SIZE], so the results don't depend on the render cadence. Results gather over
 * an interval until [clearInterval]. Preallocated, so [feed] allocates nothing.
 */
internal class HopAnalyzer(sampleRate: Int) {
    private var hopS = HOP.toFloat() / sampleRate
    private val fft = RealFft(N)
    private val window = FloatArray(N)
    private val windowSum: Float
    private val magScale: Float
    private val ring = FloatArray(N)
    private val frame = FloatArray(N)
    private val re = FloatArray(HALF + 1)
    private val im = FloatArray(HALF + 1)
    private val mag = FloatArray(HALF + 1)
    private var write = 0
    private var sinceHop = 0

    /** Compressed spectra of the last three hops, newest first, recycled in place. */
    private var c0 = FloatArray(HALF + 1)
    private var c1 = FloatArray(HALF + 1)
    private var c2 = FloatArray(HALF + 1)
    private var history = 0
    private var kickLo = 0
    private var kickHi = 0
    private var snareLo = 0
    private var snareHi = 0
    private var hatLo = 0
    private var hatHi = 0
    private var kickPower1 = 0f
    private var kickPower2 = 0f
    private var kickPower3 = 0f
    private var lastRawKick = 0f
    private var lastRawTotal = 0f
    private var totalPower1 = 0f
    private var totalPower2 = 0f
    private var totalPower3 = 0f
    private val kickOnsets = OnsetDetector(OnsetDetector.Rule.LEVEL, KICK_REFRACTORY_S)
    private val snareOnsets = OnsetDetector(OnsetDetector.Rule.SPREAD, SNARE_REFRACTORY_S)
    private val hatOnsets = OnsetDetector(OnsetDetector.Rule.SPREAD, HAT_REFRACTORY_S)
    private val tempo = TempoTracker()
    private var fluxPeak = 0f
    private var silentS = SILENT_HOLD_S

    // The interval since the last clearInterval: any onset or tick in it, and the largest normalised flux.
    var kick = false
        private set
    var snare = false
        private set
    var hat = false
        private set
    var tick = false
        private set
    var phraseTick = false
        private set
    var flux = 0f
        private set
    var hops = 0
        private set

    val silent: Boolean get() = silentS >= SILENT_HOLD_S
    val locked: Boolean get() = tempo.locked
    val bpm: Float get() = tempo.bpm
    val phase: Float get() = tempo.phase
    val count: Int get() = tempo.count

    init {
        var sum = 0.0
        for (i in 0 until N) {
            window[i] = (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / N)).toFloat()
            sum += window[i]
        }
        windowSum = sum.toFloat()
        magScale = (2.0 / sum).toFloat()
        mapBands(sampleRate)
    }

    fun reconfigure(sampleRate: Int) {
        hopS = HOP.toFloat() / sampleRate
        mapBands(sampleRate)
        reset()
    }

    fun reset() {
        ring.fill(0f)
        write = 0
        sinceHop = 0
        history = 0
        kickPower1 = 0f
        kickPower2 = 0f
        kickPower3 = 0f
        lastRawKick = 0f
        lastRawTotal = 0f
        totalPower1 = 0f
        totalPower2 = 0f
        totalPower3 = 0f
        kickOnsets.reset()
        snareOnsets.reset()
        hatOnsets.reset()
        tempo.clear()
        fluxPeak = 0f
        silentS = SILENT_HOLD_S
        clearInterval()
    }

    fun clearInterval() {
        kick = false
        snare = false
        hat = false
        tick = false
        phraseTick = false
        flux = 0f
        hops = 0
    }

    /**
     * The next [count] samples from [offset], oldest first. A backlog past [MAX_HOPS] hops only advances the
     * ring and the tempo clock, so a long stall can't make one call pay for seconds of analysis.
     */
    fun feed(mono: FloatArray, offset: Int, count: Int) {
        var skip = (sinceHop + count) / HOP - MAX_HOPS
        for (i in offset until offset + count) {
            ring[write] = mono[i]
            write = (write + 1) and MASK
            if (++sinceHop < HOP) continue
            sinceHop = 0
            if (skip > 0) {
                skip--
                tempo.skip(hopS)
                history = 0 // the next hops compare against nothing stale
                kickPower2 = Float.MAX_VALUE
                kickPower3 = Float.MAX_VALUE
                totalPower2 = Float.MAX_VALUE
                totalPower3 = Float.MAX_VALUE
            } else {
                hop()
            }
        }
    }

    private fun hop() {
        var weighted = 0f
        var sq = 0f
        for (i in 0 until N) {
            val s = ring[(write + i) and MASK]
            frame[i] = s
            weighted += window[i] * s
            sq += s * s
        }
        // The window-weighted mean makes the windowed frame's DC exactly zero, as on the display path.
        val mean = weighted / windowSum
        for (i in 0 until N) frame[i] = (frame[i] - mean) * window[i]
        fft.transform(frame, re, im)
        for (k in 0..HALF) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * magScale
        val rms = sqrt(sq / N)
        silentS = if (rms < SILENCE_RMS) minOf(silentS + hopS, SILENT_CAP_S) else 0f

        val newest = c2
        c2 = c1
        c1 = c0
        c0 = newest
        for (k in 1..hatHi) c0[k] = ln1p(GAMMA * mag[k])
        if (history < 3) history++
        var fluxAll = 0f
        if (history >= 2) {
            for (k in 1..hatHi) {
                val r = c0[k] - c1[k]
                if (r > 0f) fluxAll += r
            }
        }
        fluxAll = finite(fluxAll)

        // Two overlapping windows' mean (the guard's total too): a kick lasts past both, a noise peak mostly doesn't.
        val rawKick = bandPower(kickLo, kickHi)
        val kickPower = 0.5f * (rawKick + lastRawKick)
        lastRawKick = rawKick
        val rawTotal = bandPower(1, HALF)
        val total = 0.5f * (rawTotal + lastRawTotal)
        lastRawTotal = rawTotal
        // Rise over the lower of the two windows before this one's overlap (2 and 3 hops back), so a kick whose
        // attack straddles windows still counts whole.
        val kickRise = maxOf(0f, kickPower - minOf(kickPower2, kickPower3))
        val totalRise = maxOf(0f, total - minOf(totalPower2, totalPower3))
        kickPower3 = kickPower2
        kickPower2 = kickPower1
        kickPower1 = kickPower
        totalPower3 = totalPower2
        totalPower2 = totalPower1
        totalPower1 = total
        // Cross-talk guard: the kick band must carry at least half the spectrum's rise, so a hat never fires it.
        val k = kickOnsets.step(kickRise, kickPower, hopS, kickRise >= KICK_GUARD * totalRise)
        val s = snareOnsets.step(bandFlux(snareLo, snareHi), bandPower(snareLo, snareHi), hopS, true)
        val h = hatOnsets.step(bandFlux(hatLo, hatHi), bandPower(hatLo, hatHi), hopS, true)

        // docs/30 §3: kick and snare carry the beat; hats weigh less so eighth notes don't read as double time.
        val envelope = kickOnsets.strength + snareOnsets.strength + HAT_WEIGHT * hatOnsets.strength
        val onsets = (if (k) kickOnsets.strength else 0f) + (if (s) snareOnsets.strength else 0f) +
            (if (h) HAT_WEIGHT * hatOnsets.strength else 0f)
        tempo.step(envelope, onsets, hopS, silent, silentS)

        fluxPeak = maxOf(fluxAll, fluxPeak * (1f - hopS / (FLUX_PEAK_S + hopS)))
        val f = if (fluxPeak > FLUX_FLOOR) minOf(fluxAll / fluxPeak, 1f) else 0f
        if (f > flux) flux = f
        kick = kick || k
        snare = snare || s
        hat = hat || h
        if (tempo.tick) {
            tick = true
            if (tempo.count > 0 && tempo.count % PHRASE_BEATS == 0) phraseTick = true
        }
        hops++
    }

    /** Mean rectified rise over the band against the window just clear of this one (two hops back). */
    private fun bandFlux(lo: Int, hi: Int): Float {
        if (history < 3) return 0f
        var s = 0f
        for (k in lo..hi) {
            val r = c0[k] - c2[k]
            if (r > 0f) s += r
        }
        return finite(s / (hi - lo + 1))
    }

    private fun bandPower(lo: Int, hi: Int): Float {
        var s = 0f
        for (k in lo..hi) s += mag[k] * mag[k]
        return finite(s)
    }

    /** NaN and runaway values land on 0, so one bad sample can't poison a baseline. */
    private fun finite(x: Float): Float = if (x > 0f && x < 1e9f) x else 0f

    private fun mapBands(sampleRate: Int) {
        val binHz = sampleRate.toDouble() / N
        kickLo = classLo(KICK_LO_HZ, binHz)
        kickHi = classHi(KICK_HI_HZ, kickLo, binHz)
        snareLo = classLo(SNARE_LO_HZ, binHz)
        snareHi = classHi(SNARE_HI_HZ, snareLo, binHz)
        hatLo = classLo(HAT_LO_HZ, binHz)
        hatHi = classHi(HAT_HI_HZ, hatLo, binHz)
    }

    /** First bin at or above [hz], never DC; a class band always holds at least one bin. */
    private fun classLo(hz: Double, binHz: Double): Int = minOf(maxOf(1, ceil(hz / binHz).toInt()), HALF)

    private fun classHi(hz: Double, lo: Int, binHz: Double): Int = minOf(maxOf(lo, floor(hz / binHz).toInt()), HALF)

    companion object {
        /** Half the window: Hann windows at 50% overlap weigh every sample alike, so no hit falls between them. */
        const val HOP = 512
        private const val N = Analyzer.FFT_SIZE
        private const val MASK = N - 1
        private const val HALF = N / 2

        /** About a quarter second at 48 kHz per call; older backlog is skipped. */
        private const val MAX_HOPS = 24
        private const val KICK_LO_HZ = 40.0
        private const val KICK_HI_HZ = 120.0
        private const val SNARE_LO_HZ = 150.0
        private const val SNARE_HI_HZ = 2500.0
        private const val HAT_LO_HZ = 6000.0
        private const val HAT_HI_HZ = 16000.0
        private const val KICK_REFRACTORY_S = 0.15f
        private const val SNARE_REFRACTORY_S = 0.12f
        private const val HAT_REFRACTORY_S = 0.1f
        private const val KICK_GUARD = 0.5f

        /** log1p(GAMMA * magnitude): near-linear for quiet bins, logarithmic for loud ones. */
        private const val GAMMA = 10f
        private const val HAT_WEIGHT = 0.35f
        private const val FLUX_PEAK_S = 3f
        private const val FLUX_FLOOR = 1e-6f
        const val PHRASE_BEATS = 16

        /** −50 dBFS RMS; [silent] needs this long under it. */
        private const val SILENCE_RMS = 0.0031623f
        private const val SILENT_HOLD_S = 0.5f
        private const val SILENT_CAP_S = 3600f
    }
}
