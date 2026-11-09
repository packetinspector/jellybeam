package tv.jellybeam.visualizer.dsp

/**
 * docs/30 §3: one drum class's onset decision from its per-frame detection value: an adaptive one-second
 * threshold, a refractory period, and a baseline that restarts when the band rises out of silence. Windows
 * and warm-ups run on time, so any frame rate behaves alike. Preallocated, so [step] allocates nothing.
 */
internal class OnsetDetector(private val rule: Rule, private val refractoryS: Float) {
    enum class Rule {
        /** Above [LEVEL_RATIO] times the band's winsorised mean power: a band of a few bins, whose flux is mostly zero. */
        LEVEL,

        /** Above the median plus [SPREAD_K] interquartile ranges of the detection value: a band wide enough to average. */
        SPREAD,
    }

    /** 0 at the baseline, 1 at the threshold, capped at 2: this frame's share of the tempo envelope. */
    var strength = 0f
        private set

    /** The last second of detection values on a [GRID_HZ] grid, in arrival order and sorted. */
    private val ring = FloatArray(WINDOW)
    private val sorted = FloatArray(WINDOW)
    private var count = 0
    private var head = 0
    private var grid = 0f
    private var pending = 0f
    private var primed = false
    private var quietS = 0f
    private var refractory = 0f
    private var mean = 0f
    private var meanSamples = 0
    private var primedS = 0f
    private var primeOnsetDue = false

    fun reset() {
        count = 0
        head = 0
        grid = 0f
        pending = 0f
        primed = false
        quietS = 0f
        refractory = 0f
        mean = 0f
        meanSamples = 0
        primedS = 0f
        primeOnsetDue = false
        strength = 0f
    }

    /**
     * [d] is the detection value and [power] the band's power, both finite and non-negative, over [dtS] > 0;
     * [admit] false vetoes an onset (the cross-talk guard) without touching the baseline. True on an onset frame.
     */
    fun step(d: Float, power: Float, dtS: Float, admit: Boolean): Boolean {
        if (refractory > 0f) refractory -= dtS
        val loud = power > FLOOR_POWER
        if (!primed) {
            strength = 0f
            if (!loud) return false
            // docs/30 §3: sound rising out of silence is an onset; the baseline restarts after it, not from it.
            primed = true
            quietS = 0f
            count = 0
            head = 0
            grid = 0f
            pending = 0f
            mean = 0f
            meanSamples = 0
            primedS = 0f
            return primeOnset(admit)
        }
        if (loud) {
            quietS = 0f
        } else {
            quietS += dtS
            if (quietS >= UNPRIME_S) {
                primed = false
                strength = 0f
                return false
            }
        }
        primedS += dtS
        // docs/30 §3: the first admitted rise after priming is the onset; a hop may catch only the hit's first sliver.
        if (primeOnsetDue && primedS < PRIME_ONSET_S && loud && d > 0f) {
            if (primeOnset(admit)) return true
        }
        var base = 0f
        var threshold = Float.MAX_VALUE
        when (rule) {
            Rule.LEVEL -> if (meanSamples >= LEVEL_MIN_SAMPLES) threshold = LEVEL_RATIO * mean
            Rule.SPREAD -> if (count >= WARM_CELLS) {
                base = quantile(0.5f)
                // The floor keeps a spread of zero (a steady tone) from firing on rounding noise every refractory.
                threshold = base + maxOf(SPREAD_K * (quantile(0.75f) - quantile(0.25f)), MIN_SPREAD)
            }
        }
        strength = if (threshold < Float.MAX_VALUE && threshold > base) ((d - base) / (threshold - base)).coerceIn(0f, 2f) else 0f
        val fire = d > threshold && loud && refractory <= 0f && admit
        if (fire) refractory = refractoryS
        when (rule) {
            Rule.LEVEL -> updateMean(power, dtS)
            Rule.SPREAD -> {
                if (d > pending) pending = d
                grid += dtS * GRID_HZ
                var pushes = 0
                while (grid >= 1f && pushes < MAX_PUSHES) {
                    push(pending)
                    grid -= 1f
                    pushes++
                }
                if (pushes > 0) pending = 0f
                if (grid >= 1f) grid -= grid.toInt()
            }
        }
        return fire
    }

    private fun primeOnset(admit: Boolean): Boolean {
        primeOnsetDue = !(refractory <= 0f && admit)
        if (primeOnsetDue) return false
        refractory = refractoryS
        strength = 1f
        return true
    }

    private fun updateMean(power: Float, dtS: Float) {
        if (primedS < LEVEL_WARM_S) {
            // Warm-up: a plain average of the frames outside any refractory, so a kick never seeds its own baseline.
            if (refractory > 0f) return
            meanSamples++
            mean += (power - mean) / meanSamples
            return
        }
        meanSamples++
        // Winsorised after warm-up: a kick must not lift its own baseline.
        val x = minOf(power, LEVEL_CLIP * mean)
        mean += dtS / (WINDOW_S + dtS) * (x - mean)
    }

    private fun quantile(q: Float): Float = sorted[(q * (count - 1) + 0.5f).toInt()]

    /** Adds [v] to the window, dropping the oldest value once full; the sorted copy stays sorted. */
    private fun push(v: Float) {
        if (count == WINDOW) {
            var i = indexOf(ring[head])
            while (i < count - 1) {
                sorted[i] = sorted[i + 1]
                i++
            }
            count--
        }
        ring[head] = v
        head = (head + 1) % WINDOW
        var i = count
        while (i > 0 && sorted[i - 1] > v) {
            sorted[i] = sorted[i - 1]
            i--
        }
        sorted[i] = v
        count++
    }

    private fun indexOf(v: Float): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** −50 dB of a full-scale sine, as the band's summed bin power. */
        const val FLOOR_POWER = 1e-5f

        /** The window holds one second of [GRID_HZ] cells, each the largest value seen in it. */
        private const val GRID_HZ = 60f
        private const val WINDOW = 60
        private const val WINDOW_S = 1f
        private const val WARM_CELLS = 15
        private const val MAX_PUSHES = 4
        private const val LEVEL_WARM_S = 0.5f

        /** A baseline from a single frame is too noisy to fire on. */
        private const val LEVEL_MIN_SAMPLES = 2
        private const val LEVEL_RATIO = 5f
        private const val LEVEL_CLIP = 3f
        private const val SPREAD_K = 3.5f

        /** In compressed-magnitude units: well under a quiet hit, well over a steady tone's frame-to-frame jitter. */
        private const val MIN_SPREAD = 3e-4f

        /** Under the floor this long, the band counts as silent and its next sound re-primes it. */
        private const val UNPRIME_S = 0.5f

        /** How long after priming the onset out of silence may still land. */
        private const val PRIME_ONSET_S = 0.05f
    }
}
