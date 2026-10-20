package tv.jellybeam.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.i18n.UsLocaleRule

class SubtitleDelayTest {
    @get:Rule
    val usLocale = UsLocaleRule()

    private val strings = ResourceUiStrings.default

    @Test
    fun `a positive delay shows cues later, so the subtitle clock runs behind media time`() {
        assertEquals(8_000_000L, subtitleClockUs(mediaUs = 10_000_000L, delayMs = 2_000L))
    }

    @Test
    fun `a negative delay shows cues earlier, so the subtitle clock runs ahead`() {
        assertEquals(10_500_000L, subtitleClockUs(mediaUs = 10_000_000L, delayMs = -500L))
    }

    @Test
    fun `no delay leaves the clock alone`() {
        assertEquals(123_456L, subtitleClockUs(mediaUs = 123_456L, delayMs = 0L))
    }

    @Test
    fun `the delay clamps to plus or minus five seconds`() {
        assertEquals(SubtitleDelay.MAX_MS, SubtitleDelay.clampDelayMs(SubtitleDelay.MAX_MS + 1))
        assertEquals(-SubtitleDelay.MAX_MS, SubtitleDelay.clampDelayMs(-SubtitleDelay.MAX_MS - 1))
        assertEquals(1_500L, SubtitleDelay.clampDelayMs(1_500L))
        assertEquals(5_000L, SubtitleDelay.MAX_MS)
    }

    @Test
    fun `a tap steps 50 ms and a key held for a second steps 250 ms`() {
        assertEquals(50L, SubtitleDelay.stepForHeldMs(0))
        assertEquals(50L, SubtitleDelay.stepForHeldMs(999))
        assertEquals(250L, SubtitleDelay.stepForHeldMs(1_000))
        assertEquals(250L, SubtitleDelay.stepForHeldMs(6_000))
    }

    @Test
    fun `the delay reads as a signed integer with a minus sign, no grouping`() {
        assertEquals("+300 ms", SubtitleDelay.format(strings, 300))
        assertEquals("\u22121250 ms", SubtitleDelay.format(strings, -1250))
        assertEquals("0 ms", SubtitleDelay.format(strings, 0))
        assertEquals("+50 ms", SubtitleDelay.format(strings, 50))
        assertEquals("+5000 ms", SubtitleDelay.format(strings, SubtitleDelay.MAX_MS))
        assertEquals("\u22125000 ms", SubtitleDelay.format(strings, -SubtitleDelay.MAX_MS))
    }

    @Test
    fun `the number is not grouped whatever the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("+2500 ms", SubtitleDelay.format(strings, 2500))
            assertEquals("+2500", SubtitleDelay.signed(2500))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `ruler labels use the value's sign style without a unit`() {
        assertEquals("+2000", SubtitleDelay.signed(2000))
        assertEquals("\u22122000", SubtitleDelay.signed(-2000))
        assertEquals("0", SubtitleDelay.signed(0))
    }

    @Test
    fun `the line under the value names the direction, and zero reads in sync`() {
        assertEquals("Subtitles appear later", SubtitleDelay.direction(strings, 50))
        assertEquals("Subtitles appear earlier", SubtitleDelay.direction(strings, -50))
        assertEquals("In sync with the file", SubtitleDelay.direction(strings, 0))
    }

    private fun press(state: DelayState, key: DelayKey) = SubtitleDelay.reduce(state, key)

    @Test
    fun `Left lowers and Right raises the delay by the step for how long the key was held`() {
        assertEquals(-50L, press(DelayState(0), DelayKey.Left(0)).state.delayMs)
        assertEquals(-250L, press(DelayState(0), DelayKey.Left(1_000)).state.delayMs)
        assertEquals(50L, press(DelayState(0), DelayKey.Right(0)).state.delayMs)
        assertEquals(1_250L, press(DelayState(1_000), DelayKey.Right(1_400)).state.delayMs)
        assertTrue(press(DelayState(0), DelayKey.Left(0)).open)
        assertTrue(press(DelayState(0), DelayKey.Right(0)).open)
    }

    @Test
    fun `the delay stops at the plus and minus five seconds without wrapping`() {
        assertEquals(5_000L, press(DelayState(4_980), DelayKey.Right(0)).state.delayMs)
        assertEquals(5_000L, press(DelayState(5_000), DelayKey.Right(2_000)).state.delayMs)
        assertEquals(-5_000L, press(DelayState(-4_980), DelayKey.Left(0)).state.delayMs)
        assertEquals(-5_000L, press(DelayState(-5_000), DelayKey.Left(2_000)).state.delayMs)
    }

    @Test
    fun `a fresh Select press resets to zero and stays open`() {
        val step = press(DelayState(1_500, track = "English"), DelayKey.Reset)
        assertEquals(DelayState(0, track = "English"), step.state)
        assertTrue(step.open)
    }

    @Test
    fun `Escape and Menu close and leave the delay as it is`() {
        val step = press(DelayState(750), DelayKey.Close)
        assertFalse(step.open)
        assertEquals(750L, step.state.delayMs)
    }

    @Test
    fun `Swallow changes nothing and stays open`() {
        assertEquals(DelayStep(DelayState(750), open = true), press(DelayState(750), DelayKey.Swallow))
    }

    private fun keyOf(key: Key, type: KeyEventType = KeyEventType.KeyDown, repeatCount: Int = 0, heldMs: Long = 0L) =
        SubtitleDelay.keyOf(key, type, repeatCount, heldMs)

    @Test
    fun `the control reads Left and Right with how long they were held, and the closing keys`() {
        assertEquals(DelayKey.Left(1_200), keyOf(Key.DirectionLeft, repeatCount = 3, heldMs = 1_200))
        assertEquals(DelayKey.Right(0), keyOf(Key.DirectionRight))
        assertEquals(DelayKey.Close, keyOf(Key.Escape))
        assertEquals(DelayKey.Close, keyOf(Key.Menu))
    }

    @Test
    fun `only the first Select press resets, its repeats and release are swallowed`() {
        assertEquals(DelayKey.Reset, keyOf(Key.DirectionCenter))
        assertEquals(DelayKey.Reset, keyOf(Key.Enter))
        assertEquals(DelayKey.Swallow, keyOf(Key.DirectionCenter, repeatCount = 1, heldMs = 500))
        assertEquals(DelayKey.Swallow, keyOf(Key.Enter, repeatCount = 7, heldMs = 2_000))
        assertEquals(DelayKey.Swallow, keyOf(Key.DirectionCenter, KeyEventType.KeyUp))
        assertEquals(DelayKey.Swallow, keyOf(Key.Enter, KeyEventType.KeyUp))
    }

    @Test
    fun `Up Down Page and Channel keys are swallowed`() {
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown, Key.ChannelUp, Key.ChannelDown)) {
            assertEquals("$key", DelayKey.Swallow, keyOf(key))
        }
    }

    @Test
    fun `transport keys and Back stay unhandled so they reach the session and the Back chain`() {
        val transport = listOf(
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaStop,
            Key.MediaFastForward, Key.MediaRewind, Key.MediaNext, Key.MediaPrevious,
        )
        for (key in transport + Key.Back) assertNull("$key", keyOf(key))
    }

    @Test
    fun `an arrow key release is not the control's`() {
        assertNull(keyOf(Key.DirectionLeft, KeyEventType.KeyUp))
        assertNull(keyOf(Key.DirectionRight, KeyEventType.KeyUp))
    }

    @Test
    fun `every key sequence up to four long keeps the delay in range and closes only on an exit key`() {
        val alphabet = listOf(
            DelayKey.Left(0), DelayKey.Left(1_000), DelayKey.Right(0), DelayKey.Right(1_000),
            DelayKey.Reset, DelayKey.Close, DelayKey.Swallow,
        )
        var sequences = 0
        fun walk(state: DelayState, depth: Int) {
            if (depth == 4) return
            for (key in alphabet) {
                val step = SubtitleDelay.reduce(state, key)
                sequences++
                assertTrue("$key from $state", step.state.delayMs in -SubtitleDelay.MAX_MS..SubtitleDelay.MAX_MS)
                assertEquals("$key from $state", 0L, step.state.delayMs % 50)
                assertEquals("$key from $state", key != DelayKey.Close, step.open)
                if (key == DelayKey.Reset) assertEquals("$key from $state", 0L, step.state.delayMs)
                if (key == DelayKey.Swallow || key == DelayKey.Close) assertEquals("$key from $state", state.delayMs, step.state.delayMs)
                if (step.open) walk(step.state, depth + 1)
            }
        }
        for (start in listOf(DelayState(0), DelayState(SubtitleDelay.MAX_MS - 50), DelayState(-SubtitleDelay.MAX_MS))) walk(start, 0)
        assertTrue(sequences > 1_000)
    }

    @Test
    fun `the ruler is centred on zero until the value leaves plus or minus 2500`() {
        for (value in listOf(-2_500L, -1_000L, 0L, 50L, 2_500L)) assertEquals("$value", -2_500L, rulerWindow(value))
    }

    @Test
    fun `past 2500 the ruler slides so the marker sits at the matching edge`() {
        assertEquals(-2_450L, rulerWindow(2_550))
        assertEquals(0L, rulerWindow(5_000))
        assertEquals(-2_550L, rulerWindow(-2_550))
        assertEquals(-5_000L, rulerWindow(-5_000))
    }

    @Test
    fun `the ruler keeps the zero tick and the marker on screen for every value`() {
        for (value in -SubtitleDelay.MAX_MS..SubtitleDelay.MAX_MS step 50) {
            val start = rulerWindow(value)
            val end = start + 5_000
            assertTrue("$value", value in start..end)
            assertTrue("$value", 0L in start..end)
        }
    }

    @Test
    fun `ruler ticks sit every 250 ms inside the window`() {
        assertEquals(21, rulerTicks(-2_500).size)
        assertEquals(-2_500L, rulerTicks(-2_500).first())
        assertEquals(2_500L, rulerTicks(-2_500).last())
        // A scrolled window starts on the first tick at or after its left edge.
        assertEquals(listOf(-2_250L, -2_000L), rulerTicks(-2_450).take(2))
        assertEquals(2_500L, rulerTicks(-2_450).last())
    }

    @Test
    fun `an item reloaded or revisited gets its own delay back, a new item starts at zero`() {
        val memory = SubtitleDelayMemory()
        memory.load("item-a")
        memory.set(1_500)
        memory.load("item-a")
        assertEquals(1_500L, memory.delayMs)
        memory.load("item-b")
        assertEquals(0L, memory.delayMs)
        memory.set(-300)
        memory.load("item-a")
        assertEquals(1_500L, memory.delayMs)
        memory.load("item-b")
        assertEquals(-300L, memory.delayMs)
    }

    @Test
    fun `the remembered delay is clamped and a reset to zero is remembered`() {
        val memory = SubtitleDelayMemory()
        memory.load("item-a")
        memory.set(9_000)
        assertEquals(SubtitleDelay.MAX_MS, memory.delayMs)
        memory.set(0)
        memory.load("item-b")
        assertEquals(0L, memory.delayMs)
        memory.load("item-a")
        assertEquals(0L, memory.delayMs)
    }

    @Test
    fun `a subtitle format shows as SRT ASS PGS or VTT from a codec or mime`() {
        assertEquals("SRT", TrackChoiceText.subtitleFormat("application/x-subrip"))
        assertEquals("SRT", TrackChoiceText.subtitleFormat("srt"))
        assertEquals("ASS", TrackChoiceText.subtitleFormat("text/x-ssa"))
        assertEquals("ASS", TrackChoiceText.subtitleFormat("ass"))
        assertEquals("PGS", TrackChoiceText.subtitleFormat("application/pgs"))
        assertEquals("VTT", TrackChoiceText.subtitleFormat("text/vtt"))
        assertEquals("VTT", TrackChoiceText.subtitleFormat("webvtt"))
        assertNull(TrackChoiceText.subtitleFormat("application/x-media3-cues"))
        assertNull(TrackChoiceText.subtitleFormat(null))
    }
}
