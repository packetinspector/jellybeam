package tv.jellybeam.player

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackActivityTrackerTest {
    @Test
    fun `idle tracker returns immediately`() = runTest {
        val tracker = PlaybackActivityTracker()
        val idle = async { tracker.awaitIdle() }
        runCurrent()
        assertTrue(idle.isCompleted)
    }

    @Test
    fun `replacement waits until every prior activity is destroyed`() = runTest {
        val tracker = PlaybackActivityTracker()
        tracker.onCreated()
        tracker.onCreated()
        val idle = async { tracker.awaitIdle() }
        runCurrent()
        assertFalse(idle.isCompleted)

        tracker.onDestroyed()
        runCurrent()
        assertFalse(idle.isCompleted)

        tracker.onDestroyed()
        runCurrent()
        assertTrue(idle.isCompleted)
    }
}
