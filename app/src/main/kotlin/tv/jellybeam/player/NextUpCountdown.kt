package tv.jellybeam.player

import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import kotlin.math.ceil

/**
 * Derivation of the next-up card's countdown progress from position ticks advancing (GOAL item 6).
 * [PlaybackViewModel] owns the countdown total (`next_episode_countdown_total`); this only tracks
 * elapsed time from `positionTicks`, which advances 1:1 with wall-clock time.
 */
object NextUpCountdown {
    private const val TICKS_PER_MS = 10_000L

    /** Elapsed seconds since the next-up card first appeared, clamped to `>= 0`. */
    fun elapsedSecs(positionTicks: Long, countdownStartPositionTicks: Long): Double {
        val deltaTicks = (positionTicks - countdownStartPositionTicks).coerceAtLeast(0L)
        return (deltaTicks / TICKS_PER_MS) / 1000.0
    }

    /** Fill fraction (`0f..1f`), saturating at `1f`; `1f` also for non-positive `totalSecs`. */
    fun fraction(totalSecs: Double, elapsedSecs: Double): Float {
        if (totalSecs <= 0.0) return 1f
        return (elapsedSecs / totalSecs).coerceIn(0.0, 1.0).toFloat()
    }

    /** Depleting-rule fraction (design 1c): `1f` at the countdown's start, `0f` once it runs out --
     * the complement of [fraction], so also `0f` for a non-positive `totalSecs`.
     */
    fun remainingFraction(totalSecs: Double, elapsedSecs: Double): Float = 1f - fraction(totalSecs, elapsedSecs)

    /** Whole seconds remaining for the countdown numeral, ceiling so the first tick reads the full
     * total.
     */
    fun remainingWholeSecs(totalSecs: Double, elapsedSecs: Double): Long {
        val remaining = (totalSecs - elapsedSecs).coerceAtLeast(0.0)
        return ceil(remaining).toLong()
    }

    /** The card's countdown numeral (docs/12 §13): under a minute as `"8S"`, a minute or more as
     * `"m:ss"` (`"1:30"`); clamps to `>= 0` so a stale caller past zero still reads `"0S"`.
     */
    fun numeral(strings: UiStrings, remainingWholeSecs: Long): String {
        val clamped = remainingWholeSecs.coerceAtLeast(0L)
        if (clamped < 60L) return strings.get(R.string.player_countdown_seconds, clamped)
        val minutes = clamped / 60
        val seconds = clamped % 60
        return "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }

    /** Whether the countdown has run out -- the auto-advance trigger. */
    fun isComplete(totalSecs: Double, elapsedSecs: Double): Boolean = elapsedSecs >= totalSecs

    /**
     * What [PlaybackViewModel.evaluateNextUp] does with a fresh
     * [uniffi.jellybeam_core.StillWatchingDecision.COUNTDOWN] answer: [ADVANCE_NOW] when the
     * playhead is already [insideOutro] of an auto-skipped credits segment (the card would flash
     * for one tick before [PlaybackViewModel.evaluateAutoSkip]'s own jump) or when
     * [endedWhileDeciding] (no live position left to count down against); otherwise [SHOW_CARD].
     * Before the outro the card shows normally and its countdown runs out where the skip lands.
     */
    fun countdownOutcome(
        outroAutoSkipActive: Boolean,
        hasOutroSegment: Boolean,
        insideOutro: Boolean,
        endedWhileDeciding: Boolean,
    ): CountdownOutcome =
        if ((outroAutoSkipActive && hasOutroSegment && insideOutro) || endedWhileDeciding) {
            CountdownOutcome.ADVANCE_NOW
        } else {
            CountdownOutcome.SHOW_CARD
        }
}

/** Result of [NextUpCountdown.countdownOutcome]. */
enum class CountdownOutcome {
    /** Show the ordinary countdown card, exactly like today. */
    SHOW_CARD,
    /** Skip the card entirely; transition straight to the next item. */
    ADVANCE_NOW,
}
