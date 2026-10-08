package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.jellybeam_core.CoreException

/** docs/18 §2.1: what a playback request's completion may publish. */
class PlaybackRequestOutcomeTest {

    @Test
    fun `a plan applies only for the latest request with ownership open`() {
        assertEquals(PlanOutcome.APPLY, planOutcome(latest = true, open = true))
        assertEquals(PlanOutcome.CLOSE, planOutcome(latest = true, open = false))
        assertEquals(PlanOutcome.DISPOSE, planOutcome(latest = false, open = true))
        assertEquals(PlanOutcome.DISPOSE, planOutcome(latest = false, open = false))
    }

    @Test
    fun `a failure reaches the viewer only from its owner`() {
        val stale = CoreException.StalePlaybackSession()
        val unauthorized = CoreException.Unauthorized(account = null)
        val other = CoreException.Api("synthetic")
        val cases = listOf(
            Triple(false, true, other) to FailureOutcome.IGNORE,
            Triple(false, true, unauthorized) to FailureOutcome.IGNORE,
            Triple(false, false, unauthorized) to FailureOutcome.IGNORE,
            Triple(true, false, unauthorized) to FailureOutcome.CLOSE,
            Triple(true, false, other) to FailureOutcome.CLOSE,
            Triple(true, true, unauthorized) to FailureOutcome.REAUTHORIZE,
            Triple(true, true, other) to FailureOutcome.FINISH_WITH_MESSAGE,
            Triple(true, true, CoreException.AccountChanged()) to FailureOutcome.FINISH_WITH_MESSAGE,
        )
        for ((input, expected) in cases) {
            val (latest, open, error) = input
            for (liveSession in listOf(true, false)) {
                assertEquals("$input live=$liveSession", expected, failureOutcome(latest, open, liveSession, error))
            }
        }
        assertEquals(FailureOutcome.IGNORE, failureOutcome(latest = true, open = true, liveSession = true, error = stale))
        assertEquals(FailureOutcome.QUIET_FINISH, failureOutcome(latest = true, open = true, liveSession = false, error = stale))
    }

    @Test
    fun `only a request minted before the launch restore is re-minted`() {
        assertEquals(true, remintAfterRestore(seq = 1uL, lastSeqBeforeRestore = 1uL, open = false))
        assertEquals("a current epoch stands", false, remintAfterRestore(seq = 1uL, lastSeqBeforeRestore = 1uL, open = true))
        assertEquals("a later account change is refused, not carried over", false, remintAfterRestore(seq = 5uL, lastSeqBeforeRestore = 1uL, open = false))
        assertEquals(false, remintAfterRestore(seq = 5uL, lastSeqBeforeRestore = 0uL, open = false))
    }
}
