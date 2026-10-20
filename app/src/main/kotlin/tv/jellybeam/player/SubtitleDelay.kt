package tv.jellybeam.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings

/** docs/12 §16: the user's subtitle delay in ms, positive = cues appear later; clamped to ±[MAX_MS]. */
object SubtitleDelay {
    const val MAX_MS = 5_000L

    /** docs/12 §16: the ruler shows this much either side of 0 before it scrolls. */
    const val RULER_HALF_SPAN_MS = 2_500L

    /** docs/12 §16: ruler ticks sit every 250 ms; every second one is major. */
    const val RULER_TICK_MS = 250L

    private const val FINE_STEP_MS = 50L
    private const val COARSE_STEP_MS = 250L
    private const val COARSE_AFTER_HELD_MS = 1_000L

    fun clampDelayMs(ms: Long): Long = ms.coerceIn(-MAX_MS, MAX_MS)

    /** docs/12 §16: one Left / Right press moves 50 ms; a key held for a second moves 250 ms. */
    fun stepForHeldMs(heldMs: Long): Long = if (heldMs < COARSE_AFTER_HELD_MS) FINE_STEP_MS else COARSE_STEP_MS

    /** docs/12 §16: `+300`, `−1250` (U+2212) or `0`: the sign style of the value and the ruler labels, never grouped. */
    fun signed(ms: Long): String = when {
        ms > 0 -> "+$ms"
        ms < 0 -> "\u2212${-ms}"
        else -> "0"
    }

    /** docs/12 §16: `+300 ms`, `−1250 ms` or `0 ms`. */
    fun format(strings: UiStrings, ms: Long): String = strings.get(R.string.player_subtitle_delay_value, signed(ms))

    /** docs/12 §16: the line under the value saying what the sign means. */
    fun direction(strings: UiStrings, ms: Long): String = strings.get(
        when {
            ms > 0 -> R.string.player_subtitle_delay_later
            ms < 0 -> R.string.player_subtitle_delay_earlier
            else -> R.string.player_subtitle_delay_in_sync
        },
    )

    /** docs/12 §16: what the open delay control does with one key; `null` leaves the key unhandled (transport keys, Back, anything unlisted). */
    fun keyOf(key: Key, type: KeyEventType, repeatCount: Int, heldMs: Long): DelayKey? = when {
        // Only a press this control sees begin resets; the opening press's repeats and release belong to the picker.
        key in SELECT_KEYS -> when {
            type == KeyEventType.KeyDown && repeatCount == 0 -> DelayKey.Reset
            type == KeyEventType.KeyDown || type == KeyEventType.KeyUp -> DelayKey.Swallow
            else -> null
        }
        type != KeyEventType.KeyDown -> null
        key == Key.DirectionLeft -> DelayKey.Left(heldMs)
        key == Key.DirectionRight -> DelayKey.Right(heldMs)
        key == Key.Escape || key == Key.Menu -> DelayKey.Close
        key == Key.DirectionUp || key == Key.DirectionDown || PageKeys.isPageKey(key) -> DelayKey.Swallow
        else -> null
    }

    /** docs/12 §16: the transition table of the delay control; [DelayStep.open] false means Back-equivalent exit. */
    fun reduce(state: DelayState, key: DelayKey): DelayStep = when (key) {
        is DelayKey.Left -> DelayStep(state.copy(delayMs = clampDelayMs(state.delayMs - stepForHeldMs(key.heldMs))), open = true)
        is DelayKey.Right -> DelayStep(state.copy(delayMs = clampDelayMs(state.delayMs + stepForHeldMs(key.heldMs))), open = true)
        DelayKey.Reset -> DelayStep(state.copy(delayMs = 0L), open = true)
        DelayKey.Close -> DelayStep(state, open = false)
        DelayKey.Swallow -> DelayStep(state, open = true)
    }
}

/**
 * docs/12 §16: the delay per item for this process; an item reloaded or revisited gets its own back, a new item starts at 0.
 * Main thread only, except [delayMs], which renderers read from the playback thread.
 */
class SubtitleDelayMemory {
    private val byItem = mutableMapOf<String, Long>()
    private var itemId: String? = null

    /** The loaded item's delay. */
    @Volatile
    var delayMs: Long = 0L
        private set

    /** Switches to [itemId] and its stored delay (0 when it has none). */
    fun load(itemId: String) {
        this.itemId = itemId
        delayMs = byItem[itemId] ?: 0L
    }

    /** Sets the loaded item's delay, clamped, and keeps it for the item. */
    fun set(ms: Long) {
        delayMs = SubtitleDelay.clampDelayMs(ms)
        itemId?.let { byItem[it] = delayMs }
    }
}

/**
 * docs/12 §16: the ruler's left edge in ms. It spans 2 x [SubtitleDelay.RULER_HALF_SPAN_MS] around 0 until the
 * value leaves it, then slides just far enough to keep the marker at the matching edge (0 stays visible).
 */
fun rulerWindow(valueMs: Long): Long {
    val half = SubtitleDelay.RULER_HALF_SPAN_MS
    return when {
        valueMs > half -> valueMs - 2 * half
        valueMs < -half -> valueMs
        else -> -half
    }
}

/** docs/12 §16: the tick positions (ms) inside the window that starts at [startMs]. */
fun rulerTicks(startMs: Long): List<Long> {
    val step = SubtitleDelay.RULER_TICK_MS
    val first = Math.floorDiv(startMs + step - 1, step) * step
    val end = startMs + 2 * SubtitleDelay.RULER_HALF_SPAN_MS
    return generateSequence(first) { it + step }.takeWhile { it <= end }.toList()
}

/** The open delay control: the live [delayMs] and the shown subtitle's [track] name. */
data class DelayState(val delayMs: Long, val track: String = "")

/** One key as the delay control reads it (docs/12 §16). */
sealed interface DelayKey {
    /** [heldMs] is how long the key has been down. */
    data class Left(val heldMs: Long) : DelayKey

    data class Right(val heldMs: Long) : DelayKey

    /** A fresh Select press: back to 0, control stays open. */
    data object Reset : DelayKey

    /** Escape, Menu; Back reaches the same exit through the screen's `BackHandler`. */
    data object Close : DelayKey

    /** Up, Down, Page, Channel and Select repeats and releases: consumed so nothing underneath acts. */
    data object Swallow : DelayKey
}

/** The control's next [state]; [open] false closes it. */
data class DelayStep(val state: DelayState, val open: Boolean)

/** docs/12 §16: the media time subtitles are drawn for, so a +2000 ms delay at 10 s shows what was due at 8 s. */
fun subtitleClockUs(mediaUs: Long, delayMs: Long): Long = mediaUs - delayMs * 1000
