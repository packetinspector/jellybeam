package tv.jellybeam.visualizer

/**
 * docs/30 §3: one frame of analysed audio, written by the analyser and read by the renderer on the
 * GL thread. Preallocated and reused every frame, so the per-frame path allocates nothing.
 */
class Features {
    /** 64 log-spaced bands, 30 Hz–16 kHz, dB −70..0 mapped to 0..1, attack instant, release 1.2/s. */
    val bands = FloatArray(BANDS)

    /** Peak caps per band: hold 0.5 s, then fall 1.5/s; never below the band. */
    val peaks = FloatArray(BANDS)

    /** The newest [WAVE] mono samples, −1..1, oldest first. */
    val wave = FloatArray(WAVE)

    /** The newest [WAVE] left/right samples, −1..1, oldest first (XY scope). */
    val waveL = FloatArray(WAVE)
    val waveR = FloatArray(WAVE)

    /** True on the frame a kick onset is detected (same as [kick]; kept for v1 patterns). */
    var beat = false

    /** 1 on a beat, decaying to 0 over about 0.4 s. */
    var beatPulse = 0f

    /** Seconds since each of the last [BEAT_SLOTS] beats, newest first; [NO_BEAT] when empty. */
    val beatAges = FloatArray(BEAT_SLOTS) { NO_BEAT }

    /** RMS level of the window, 0..1. */
    var level = 0f

    /** Mean band value over the bass (bands 0–9), mid (10–39) and treble (40–63) ranges, 0..1. */
    var bass = 0f
    var mid = 0f
    var treble = 0f

    /** Seconds of analysed time; advances only while audio advances. */
    var timeS = 0f

    // docs/30 §3 (E5): per-class onsets, each true on its onset frame; pulses are 1 then fall to 0 over 0.25 s.
    var kick = false
    var snare = false
    var hat = false
    var kickPulse = 0f
    var snarePulse = 0f
    var hatPulse = 0f

    /** Half-wave-rectified spectral flux this frame, 0..1. */
    var flux = 0f

    /** RMS envelopes: about 0.1 s and about 3 s, 0..1. */
    var loudShort = 0f
    var loudLong = 0f

    /** Tempo from the onset envelope's autocorrelation, 60–200 BPM; 0 while unlocked. */
    var tempoBpm = 0f
    var tempoLocked = false

    /** 0 at a predicted beat, rising to 1; 0 while unlocked. */
    var beatPhase = 0f

    /** True on the frame the beat phase wraps (locked only); [beatCount] counts them since lock. */
    var beatTick = false
    var beatCount = 0

    /** [beatCount] / 16; [phraseTick] is true on the tick that completes a phrase. */
    var phrase = 0
    var phraseTick = false

    /** True while the input sits below the silence floor. */
    var silent = true

    /** True when every band is at the floor and no peak is above it: nothing left to draw. */
    val settled: Boolean
        get() {
            for (i in 0 until BANDS) if (bands[i] > 0f || peaks[i] > 0f) return false
            return beatPulse <= 0f
        }

    fun copyFrom(o: Features) {
        o.bands.copyInto(bands)
        o.peaks.copyInto(peaks)
        o.wave.copyInto(wave)
        o.waveL.copyInto(waveL)
        o.waveR.copyInto(waveR)
        o.beatAges.copyInto(beatAges)
        beat = o.beat
        beatPulse = o.beatPulse
        level = o.level
        bass = o.bass
        mid = o.mid
        treble = o.treble
        timeS = o.timeS
        kick = o.kick
        snare = o.snare
        hat = o.hat
        kickPulse = o.kickPulse
        snarePulse = o.snarePulse
        hatPulse = o.hatPulse
        flux = o.flux
        loudShort = o.loudShort
        loudLong = o.loudLong
        tempoBpm = o.tempoBpm
        tempoLocked = o.tempoLocked
        beatPhase = o.beatPhase
        beatTick = o.beatTick
        beatCount = o.beatCount
        phrase = o.phrase
        phraseTick = o.phraseTick
        silent = o.silent
    }

    fun clear() {
        bands.fill(0f)
        peaks.fill(0f)
        wave.fill(0f)
        waveL.fill(0f)
        waveR.fill(0f)
        beatAges.fill(NO_BEAT)
        beat = false
        beatPulse = 0f
        level = 0f
        bass = 0f
        mid = 0f
        treble = 0f
        kick = false
        snare = false
        hat = false
        kickPulse = 0f
        snarePulse = 0f
        hatPulse = 0f
        flux = 0f
        loudShort = 0f
        loudLong = 0f
        tempoBpm = 0f
        tempoLocked = false
        beatPhase = 0f
        beatTick = false
        beatCount = 0
        phrase = 0
        phraseTick = false
        silent = true
    }

    companion object {
        const val BANDS = 64
        const val WAVE = 512
        const val BEAT_SLOTS = 8
        const val NO_BEAT = 1.0e6f
    }
}
