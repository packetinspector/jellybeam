package tv.jellybeam.visualizer.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/**
 * docs/30 §3: tempo and beat phase from the onset-strength envelope. The envelope is resampled to an
 * [ENV_HZ] grid so a lag means the same time at any display rate, and its autocorrelation decays
 * incrementally, so no frame pays for a whole window. Preallocated, so [step] allocates nothing.
 */
internal class TempoTracker {
    var locked = false
        private set

    /** True on the call in which a predicted beat passed (locked and not silent only). */
    var tick = false
        private set

    /** Ticks since the lock started; 0 while unlocked or silent. */
    var count = 0
        private set

    val bpm: Float get() = if (locked) ENV_HZ * 60f / period else 0f

    /** 0 at a predicted beat, rising to 1. */
    val phase: Float get() = if (locked) (theta + LEAD_CELLS / period).let { it - floor(it) }.toFloat() else 0f

    /** Mean-removed envelope for the autocorrelation; [gated] keeps only onset frames, for the phase. */
    private val env = FloatArray(RING)
    private val gated = FloatArray(RING)
    private val acf = FloatArray(MAX_LAG + 1)
    private val weight = FloatArray(MAX_LAG + 1)
    private val prior = FloatArray(CANDIDATES)
    private var head = 0
    private var filled = 0
    private var mean = 0f
    private var grid = 0.0
    private var pendingEnv = 0f
    private var pendingGated = 0f
    private var candidate = 0f
    private var candidateS = 0f
    private var lostS = 0f

    /** Envelope frames per beat while locked. */
    private var period = 0f

    /** Beats since an arbitrary origin; the fraction is the phase. */
    private var theta = 0.0
    private var lastBeat = 0L
    private var phaseScore = 0f
    private var clean = true
    private var harmonics = 0

    init {
        for (i in 0 until CANDIDATES) prior[i] = priorOf(P_MIN + i * P_STEP)
    }

    fun clear() {
        env.fill(0f)
        gated.fill(0f)
        acf.fill(0f)
        weight.fill(0f)
        head = 0
        filled = 0
        mean = 0f
        grid = 0.0
        pendingEnv = 0f
        pendingGated = 0f
        candidate = 0f
        unlock()
        clean = true
    }

    /**
     * [envelope] is this frame's summed class strength, [onsets] the same over onset frames only;
     * [silentS] is how long the input has been under the silence floor.
     */
    fun step(envelope: Float, onsets: Float, dtS: Float, silent: Boolean, silentS: Float) {
        tick = false
        // docs/30 §3: four seconds of silence unlocks and forgets, so the next song re-primes from nothing.
        if (silentS >= UNLOCK_SILENCE_S) {
            if (!clean) clear()
            return
        }
        if (envelope > pendingEnv) pendingEnv = envelope
        if (onsets > pendingGated) pendingGated = onsets
        grid += dtS * ENV_HZ
        var cells = grid.toInt()
        grid -= cells
        // A hitch's backlog advances the ring and phase cheaply; only the newest few cells pay for the search.
        while (cells > MAX_PUSHES) {
            skipCell()
            cells--
        }
        while (cells > 0) {
            push(pendingEnv, pendingGated, silent)
            pendingEnv = 0f
            pendingGated = 0f
            cells--
        }
        if (silent) count = 0
    }

    /** Advances [dtS] of envelope time with no onsets and no search, for analysis that was skipped. */
    fun skip(dtS: Float) {
        tick = false
        grid += dtS * ENV_HZ
        val cells = grid.toInt()
        grid -= cells
        for (i in 0 until cells) skipCell()
    }

    /** One envelope cell with no onsets, without the autocorrelation update or the period search. */
    private fun skipCell() {
        clean = false
        env[head] = -mean
        gated[head] = 0f
        head = (head + 1) and MASK
        if (filled < RING) filled++
        if (locked) theta += 1.0 / period
    }

    private fun push(o: Float, g: Float, silent: Boolean) {
        clean = false
        mean += (o - mean) * MEAN_RATE
        val e = o - mean
        env[head] = e
        gated[head] = g
        val newest = head
        head = (head + 1) and MASK
        if (filled < RING) filled++
        val lags = minOf(MAX_LAG, filled - 1)
        for (lag in 0..lags) {
            acf[lag] = LAMBDA * acf[lag] + e * env[(newest - lag) and MASK]
            weight[lag] = LAMBDA * weight[lag] + 1f
        }
        // Every candidate is scored over the same harmonics, those the longest period has in the ring.
        harmonics = minOf(HARMONICS, (filled - 2) / P_MAX.toInt())
        if (harmonics < 1) return
        val r0 = acf[0] / weight[0]
        if (!(r0 > 0f)) return

        var best = 0f
        var bestP = 0f
        for (i in 0 until CANDIDATES) {
            val p = P_MIN + i * P_STEP
            val c = comb(p) * prior[i]
            if (c > best) {
                best = c
                bestP = p
            }
        }
        if (bestP > 0f) {
            // A beat at period p also repeats at 2p, so the longer period always scores; prefer the shorter when close.
            val half = bestP * 0.5f
            if (half >= P_MIN) {
                val c = comb(half) * priorOf(half)
                if (c >= HALF_RATIO * best) {
                    best = c
                    bestP = half
                }
            }
            val p = refine(bestP)
            val confidence = best / r0
            if (candidate > 0f && abs(p - candidate) < CANDIDATE_TOL * candidate) {
                candidateS += PUSH_S
                candidate += 0.1f * (p - candidate)
            } else {
                candidate = p
                candidateS = 0f
            }
            if (confidence >= CONF_LOCK && candidateS >= LOCK_HOLD_S) {
                if (!locked || abs(candidate - period) > CANDIDATE_TOL * period) {
                    lock(candidate)
                    return
                }
                period += 0.05f * (candidate - period)
            }
            lostS = if (confidence < CONF_UNLOCK) lostS + PUSH_S else 0f
        } else {
            lostS += PUSH_S
        }
        if (!locked) return
        if (lostS >= LOST_S) {
            unlock()
            return
        }
        val step = 1.0 / period
        var th = theta + step
        if (!silent) {
            val phi = phaseOf(period)
            if (phaseScore > MIN_PHASE_SCORE) {
                var err = phi / period - (th - floor(th))
                err -= floor(err + 0.5)
                th += PLL_GAIN * err
            }
        }
        // The tick goes to the frame nearest the predicted beat; a backward nudge never ticks the same beat twice.
        val beat = floor(th + (LEAD_CELLS + 0.5) * step).toLong()
        if (beat > lastBeat) {
            lastBeat = beat
            if (!silent) {
                tick = true
                count++
            }
        }
        theta = th
    }

    /** A relock (tempo change while locked) keeps the beat count, so phrases run on. */
    private fun lock(p: Float) {
        if (!locked) {
            count = 0
            tick = false
        }
        locked = true
        period = p
        theta = (phaseOf(p) / p).toDouble()
        lastBeat = floor(theta + (LEAD_CELLS + 0.5) / p).toLong()
        lostS = 0f
    }

    private fun unlock() {
        locked = false
        period = 0f
        theta = 0.0
        lastBeat = 0L
        count = 0
        candidateS = 0f
        lostS = 0f
        tick = false
    }

    /** Normalised autocorrelation at lag [x] envelope frames, interpolated; 0 where the ring is too short. */
    private fun r(x: Float): Float {
        val i = x.toInt()
        if (i + 1 > MAX_LAG || i + 1 >= filled) return 0f
        val f = x - i
        return (acf[i] / weight[i]) * (1f - f) + (acf[i + 1] / weight[i + 1]) * f
    }

    /** Mean positive autocorrelation over the first [harmonics] multiples of [p]. */
    private fun comb(p: Float): Float {
        var sum = 0f
        for (h in 1..harmonics) {
            val v = r(h * p)
            if (v > 0f) sum += v
        }
        return sum / harmonics
    }

    /** Sub-frame period: the autocorrelation centroid around each harmonic, higher harmonics weighted more. */
    private fun refine(p: Float): Float {
        var num = 0f
        var den = 0f
        for (h in 1..harmonics) {
            val centre = (h * p + 0.5f).toInt()
            val lo = maxOf(1, centre - 1)
            val hi = minOf(MAX_LAG, centre + 1, filled - 1)
            var sum = 0f
            var moment = 0f
            for (lag in lo..hi) {
                val v = acf[lag] / weight[lag]
                if (v > 0f) {
                    sum += v
                    moment += v * lag
                }
            }
            if (sum > 0f) {
                num += moment
                den += h * sum
            }
        }
        return if (den > 0f) num / den else p
    }

    /** Envelope frames since the last beat that best explain the recent onsets at period [p]; sets [phaseScore]. */
    private fun phaseOf(p: Float): Float {
        val beats = ((filled - 2) / p).toInt()
        val newest = (head - 1) and MASK
        var best = -1f
        var bestPhi = 0f
        var phi = 0f
        while (phi < p) {
            var s = 0f
            for (k in 0 until beats) {
                val x = phi + k * p
                val i = x.toInt()
                val f = x - i
                val v = gated[(newest - i) and MASK] * (1f - f) + gated[(newest - i - 1) and MASK] * f
                s += v * (1f - 0.5f * k / beats)
            }
            if (s > best) {
                best = s
                bestPhi = phi
            }
            phi += PHASE_STEP
        }
        phaseScore = best
        return bestPhi
    }

    companion object {
        const val ENV_HZ = 60f
        private const val RING = 256
        private const val MASK = RING - 1
        private const val PUSH_S = 1f / ENV_HZ

        /** 200 to 60 BPM, in envelope frames, on a quarter-frame grid. */
        private const val P_MIN = 18f
        private const val P_MAX = 60f
        private const val P_STEP = 0.25f
        private const val CANDIDATES = 169 // (P_MAX - P_MIN) / P_STEP + 1
        private const val HARMONICS = 4
        private const val MAX_LAG = HARMONICS * 60 // HARMONICS * P_MAX
        private const val MAX_PUSHES = 4
        private const val ACF_TAU_S = 3f
        private val LAMBDA = exp(-1.0 / (ENV_HZ * ACF_TAU_S)).toFloat()
        private const val MEAN_RATE = 1f / (ENV_HZ * 2f)
        private const val HALF_RATIO = 0.85f
        private const val CANDIDATE_TOL = 0.03f
        private const val CONF_LOCK = 0.25f
        private const val CONF_UNLOCK = 0.12f
        private const val LOCK_HOLD_S = 2f
        private const val LOST_S = 4f
        private const val UNLOCK_SILENCE_S = 4f
        private const val PLL_GAIN = 0.15

        /**
         * Onsets are found about one display frame after they are heard (the window must hold the attack, and the
         * heard sample is the window's centre), so phase and ticks read that far ahead.
         */
        private const val LEAD_CELLS = 1.0
        private const val PHASE_STEP = 0.25f
        private const val MIN_PHASE_SCORE = 0.5f

        /** Flat over 90–180 BPM, falling half an octave per sigma outside, against octave errors. */
        private fun priorOf(p: Float): Float {
            val bpm = ENV_HZ * 60f / p
            val octaves = when {
                bpm < 90f -> ln(90f / bpm) / LN2
                bpm > 180f -> ln(bpm / 180f) / LN2
                else -> return 1f
            }
            val z = octaves / 0.5f
            return exp(-0.5f * z * z)
        }

        private val LN2 = ln(2f)
    }
}
