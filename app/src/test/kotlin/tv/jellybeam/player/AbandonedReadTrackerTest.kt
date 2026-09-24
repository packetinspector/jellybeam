package tv.jellybeam.player

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AbandonedReadTracker.CANCEL_THRESHOLD_BYTES] threshold matrix. */
class AbandonedReadTrackerTest {

    @Test
    fun `unknown remaining length cancels`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(C.LENGTH_UNSET.toLong())

        assertTrue(tracker.shouldCancel())
    }

    @Test
    fun `never opened at all also cancels, same as unknown`() {
        // Media3 can close a DataSource whose open() itself failed/threw.
        assertTrue(AbandonedReadTracker().shouldCancel())
    }

    @Test
    fun `remaining over the threshold cancels`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(AbandonedReadTracker.CANCEL_THRESHOLD_BYTES + 1)

        assertTrue(tracker.shouldCancel())
    }

    @Test
    fun `remaining exactly at the threshold does not cancel`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(AbandonedReadTracker.CANCEL_THRESHOLD_BYTES)

        assertFalse(tracker.shouldCancel())
    }

    @Test
    fun `remaining well under the threshold does not cancel`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(100_000L)

        assertFalse(tracker.shouldCancel())
    }

    @Test
    fun `reading down to zero remaining does not cancel`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(500_000L)
        tracker.onRead(500_000)

        assertFalse(tracker.shouldCancel())
    }

    @Test
    fun `reading part way still cancels if what's left is over the threshold`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(500_000L)
        tracker.onRead(10_000)

        assertTrue(tracker.shouldCancel())
    }

    @Test
    fun `reading down under the threshold no longer cancels`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(300_000L)
        tracker.onRead(100_000)

        assertFalse(tracker.shouldCancel())
    }

    @Test
    fun `an observed end-of-stream read never cancels, even with an unknown length`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(C.LENGTH_UNSET.toLong())
        tracker.onRead(C.RESULT_END_OF_INPUT)

        assertFalse(tracker.shouldCancel())
    }

    @Test
    fun `a custom threshold is honored`() {
        val tracker = AbandonedReadTracker(cancelThresholdBytes = 10L)
        tracker.onOpened(11L)

        assertTrue(tracker.shouldCancel())
    }

    @Test
    fun `re-opening resets a previously observed end-of-stream`() {
        val tracker = AbandonedReadTracker()
        tracker.onOpened(10L)
        tracker.onRead(C.RESULT_END_OF_INPUT)
        assertFalse(tracker.shouldCancel())

        tracker.onOpened(C.LENGTH_UNSET.toLong())

        assertTrue("a fresh open for a new range must not still read as EOF", tracker.shouldCancel())
    }
}
