package tv.jellybeam.player

import tv.jellybeam.i18n.ResourceUiStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpCountdownTest {
    private val strings = ResourceUiStrings.default


    @Test
    fun `elapsedSecs derives from how far position has advanced past the countdown start`() {
        // 3s worth of ticks (10_000_000 ticks/sec).
        assertEquals(3.0, NextUpCountdown.elapsedSecs(230_000_000L, 200_000_000L), 0.0001)
        assertEquals(0.0, NextUpCountdown.elapsedSecs(200_000_000L, 200_000_000L), 0.0001)
    }

    @Test
    fun `elapsedSecs never goes negative even if position moved backward`() {
        assertEquals(0.0, NextUpCountdown.elapsedSecs(100_000_000L, 200_000_000L), 0.0001)
    }

    @Test
    fun `fraction saturates between 0 and 1`() {
        assertEquals(0f, NextUpCountdown.fraction(10.0, 0.0), 0.0001f)
        assertEquals(0.5f, NextUpCountdown.fraction(10.0, 5.0), 0.0001f)
        assertEquals(1f, NextUpCountdown.fraction(10.0, 10.0), 0.0001f)
        assertEquals(1f, NextUpCountdown.fraction(10.0, 20.0), 0.0001f)
    }

    @Test
    fun `fraction reports a full bar immediately for a non-positive total`() {
        assertEquals(1f, NextUpCountdown.fraction(0.0, 0.0), 0.0001f)
    }

    @Test
    fun `remainingWholeSecs ceilings down to zero without a premature rounding`() {
        assertEquals(10L, NextUpCountdown.remainingWholeSecs(10.0, 0.0))
        assertEquals(1L, NextUpCountdown.remainingWholeSecs(10.0, 9.1))
        assertEquals(0L, NextUpCountdown.remainingWholeSecs(10.0, 10.0))
        // Never negative once elapsed exceeds total.
        assertEquals(0L, NextUpCountdown.remainingWholeSecs(10.0, 15.0))
    }

    @Test
    fun `isComplete only flips true once elapsed reaches the total`() {
        assertFalse(NextUpCountdown.isComplete(10.0, 9.999))
        assertTrue(NextUpCountdown.isComplete(10.0, 10.0))
        assertTrue(NextUpCountdown.isComplete(10.0, 10.5))
    }

    // -- remainingFraction: the depleting-rule complement of fraction -------

    @Test
    fun `remainingFraction is a full rule right at the countdown start`() {
        assertEquals(1f, NextUpCountdown.remainingFraction(10.0, 0.0), 0.0001f)
    }

    @Test
    fun `remainingFraction is halfway at the midpoint`() {
        assertEquals(0.5f, NextUpCountdown.remainingFraction(10.0, 5.0), 0.0001f)
    }

    @Test
    fun `remainingFraction is empty once elapsed reaches the total`() {
        assertEquals(0f, NextUpCountdown.remainingFraction(10.0, 10.0), 0.0001f)
    }

    @Test
    fun `remainingFraction never goes negative past the total`() {
        assertEquals(0f, NextUpCountdown.remainingFraction(10.0, 20.0), 0.0001f)
    }

    @Test
    fun `remainingFraction is empty for a non-positive total rather than dividing by zero`() {
        assertEquals(0f, NextUpCountdown.remainingFraction(0.0, 0.0), 0.0001f)
    }

    // -- numeral: "8S" under a minute, "m:ss" at or past it ------------------

    @Test
    fun `numeral reads zero as 0S`() {
        assertEquals("0S", NextUpCountdown.numeral(strings, 0L))
    }

    @Test
    fun `numeral reads a single-digit second count as NS`() {
        assertEquals("8S", NextUpCountdown.numeral(strings, 8L))
    }

    @Test
    fun `numeral reads the last second below a minute as 59S`() {
        assertEquals("59S", NextUpCountdown.numeral(strings, 59L))
    }

    @Test
    fun `numeral switches to m colon ss exactly at one minute`() {
        assertEquals("1:00", NextUpCountdown.numeral(strings, 60L))
    }

    @Test
    fun `numeral formats a minute plus seconds`() {
        assertEquals("1:30", NextUpCountdown.numeral(strings, 90L))
    }

    @Test
    fun `numeral formats an hour-plus total as raw minutes, never switching format`() {
        assertEquals("60:00", NextUpCountdown.numeral(strings, 3_600L))
    }

    @Test
    fun `numeral clamps a negative count to 0S rather than going negative`() {
        assertEquals("0S", NextUpCountdown.numeral(strings, -5L))
    }

    // -- countdownOutcome (outro-auto-skip + still-watching bug fix) -------

    @Test
    fun `countdownOutcome shows the ordinary card when outro auto-skip is not in play and EOF was not raced`() {
        assertEquals(
            CountdownOutcome.SHOW_CARD,
            NextUpCountdown.countdownOutcome(outroAutoSkipActive = false, hasOutroSegment = false, insideOutro = false, endedWhileDeciding = false),
        )
    }

    @Test
    fun `countdownOutcome shows the ordinary card when outro auto-skip is configured but this item has no outro segment`() {
        assertEquals(
            CountdownOutcome.SHOW_CARD,
            NextUpCountdown.countdownOutcome(outroAutoSkipActive = true, hasOutroSegment = false, insideOutro = true, endedWhileDeciding = false),
        )
    }

    @Test
    fun `countdownOutcome shows the ordinary card when this item has an outro segment but it is not configured AutoSkip`() {
        assertEquals(
            CountdownOutcome.SHOW_CARD,
            NextUpCountdown.countdownOutcome(outroAutoSkipActive = false, hasOutroSegment = true, insideOutro = true, endedWhileDeciding = false),
        )
    }

    @Test
    fun `countdownOutcome shows the card before an auto-skipped outro -- the countdown runs out where the skip lands`() {
        assertEquals(
            CountdownOutcome.SHOW_CARD,
            NextUpCountdown.countdownOutcome(outroAutoSkipActive = true, hasOutroSegment = true, insideOutro = false, endedWhileDeciding = false),
        )
    }

    @Test
    fun `countdownOutcome advances immediately when the playhead is already inside an auto-skipped outro`() {
        assertEquals(
            CountdownOutcome.ADVANCE_NOW,
            NextUpCountdown.countdownOutcome(outroAutoSkipActive = true, hasOutroSegment = true, insideOutro = true, endedWhileDeciding = false),
        )
    }

    @Test
    fun `countdownOutcome advances immediately whenever EOF was reached while the decision was pending, regardless of outro auto-skip`() {
        assertEquals(
            CountdownOutcome.ADVANCE_NOW,
            NextUpCountdown.countdownOutcome(outroAutoSkipActive = false, hasOutroSegment = false, insideOutro = false, endedWhileDeciding = true),
        )
        assertEquals(
            CountdownOutcome.ADVANCE_NOW,
            NextUpCountdown.countdownOutcome(outroAutoSkipActive = true, hasOutroSegment = true, insideOutro = false, endedWhileDeciding = true),
        )
    }
}
