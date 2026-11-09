package tv.jellybeam.visualizer.pattern

import tv.jellybeam.visualizer.Features

/**
 * docs/30 §5 (E6): when the next pattern starts by itself. On a beat once [BEATS] beats have passed
 * with the tempo locked, or after [UNLOCKED_S] of audio without a lock; never while silent, never when
 * [enabled] is off. A manual switch calls [reset]. Pure: audio time and features only.
 */
class AutoScheduler {
    var enabled = true

    private var beats = 0
    private var unlockedS = 0f

    fun reset() {
        beats = 0
        unlockedS = 0f
    }

    /** True on the frame a transition should start; the caller then [reset]s once it is done. */
    fun update(f: Features, dtS: Float): Boolean {
        if (!enabled || f.silent) return false
        if (f.tempoLocked) {
            unlockedS = 0f
            if (f.beatTick) {
                beats++
                if (beats >= BEATS) return true
            }
        } else {
            unlockedS += dtS
            if (unlockedS >= UNLOCKED_S) return true
        }
        return false
    }

    companion object {
        const val BEATS = 64
        const val UNLOCKED_S = 45f
    }
}

/** docs/30 §5 (E6): a 1.5 s crossfade from [from] to [to], advanced by audio time so a pause holds it. */
class Crossfade {
    var from: Pattern = Pattern.BARS
        private set
    var to: Pattern = Pattern.BARS
        private set

    /** 0..1 progress; [active] while below 1. */
    var t = 1f
        private set

    val active: Boolean get() = t < 1f

    fun start(from: Pattern, to: Pattern) {
        this.from = from
        this.to = to
        t = 0f
    }

    /** A manual switch: the crossfade ends at once. */
    fun cancel() {
        t = 1f
    }

    /** Advances by [dtS]; true on the frame it completes. */
    fun advance(dtS: Float): Boolean {
        if (!active) return false
        t = (t + dtS / DURATION_S).coerceAtMost(1f)
        return t >= 1f
    }

    companion object {
        const val DURATION_S = 1.5f
    }
}
