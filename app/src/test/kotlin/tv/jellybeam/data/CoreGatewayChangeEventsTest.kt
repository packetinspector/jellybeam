package tv.jellybeam.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.jellybeam_core.ChangeEvent

/**
 * Pins that [CoreGateway.changeEvents] is one shared, fan-out broadcast, not "whoever registered
 * most recently wins" — the shape Home + an open Library + an open Detail rely on simultaneously.
 * [FakeCoreGateway.emitChange] backs this with the same contract [RealCoreGateway] has. Collectors
 * are plain `launch`ed and explicitly cancelled in `finally` to avoid leaving one running past
 * `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreGatewayChangeEventsTest {

    @Test
    fun `two concurrent collectors both receive the same emitted event`() = runTest {
        val gateway = FakeCoreGateway()
        val receivedByFirst = mutableListOf<ChangeEvent>()
        val receivedBySecond = mutableListOf<ChangeEvent>()

        val firstJob = launch { gateway.changeEvents().collect { receivedByFirst.add(it) } }
        val secondJob = launch { gateway.changeEvents().collect { receivedBySecond.add(it) } }
        try {
            runCurrent() // let both collectors subscribe before anything is emitted

            gateway.emitChange(ChangeEvent.Upserted(ids = listOf("item-1"), libraryId = null))
            runCurrent()

            assertEquals(listOf(ChangeEvent.Upserted(ids = listOf("item-1"), libraryId = null)), receivedByFirst)
            assertEquals(listOf(ChangeEvent.Upserted(ids = listOf("item-1"), libraryId = null)), receivedBySecond)
        } finally {
            firstJob.cancel()
            secondJob.cancel()
        }
    }
}
