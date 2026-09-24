package tv.jellybeam.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressReportGateTest {

    @Test
    fun `first observed pause state is always reported`() {
        assertTrue(ProgressReportGate.shouldReportPaused(previousPaused = null, newPaused = false))
        assertTrue(ProgressReportGate.shouldReportPaused(previousPaused = null, newPaused = true))
    }

    @Test
    fun `an unchanged pause state is not re-reported`() {
        assertFalse(ProgressReportGate.shouldReportPaused(previousPaused = true, newPaused = true))
        assertFalse(ProgressReportGate.shouldReportPaused(previousPaused = false, newPaused = false))
    }

    @Test
    fun `a real pause-resume edge is reported`() {
        assertTrue(ProgressReportGate.shouldReportPaused(previousPaused = false, newPaused = true))
        assertTrue(ProgressReportGate.shouldReportPaused(previousPaused = true, newPaused = false))
    }

    @Test
    fun `position is only report-worthy while actually playing`() {
        assertTrue(ProgressReportGate.shouldReportPosition(isPlaying = true))
        assertFalse(ProgressReportGate.shouldReportPosition(isPlaying = false))
    }
}
