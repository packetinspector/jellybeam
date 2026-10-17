package tv.jellybeam.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake's suspend gates hold a call after it is counted and release it on completion; no gate answers at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeCoreGatewayGatesTest {

    @Test
    fun `a gated call is counted, held, and recorded only once the gate is released`() = runTest {
        val gateway = FakeCoreGateway()
        val gate = CompletableDeferred<Unit>()
        gateway.setSettingsGate = gate

        launch { gateway.setSettings(defaultTestSettings().copy(homeShelfSize = 5u)) }
        runCurrent()

        assertEquals(1, gateway.setSettingsEntered)
        assertTrue("held at the gate", gateway.setSettingsCalls.isEmpty())

        gate.complete(Unit)
        runCurrent()

        assertEquals(1, gateway.setSettingsCalls.size)
    }

    @Test
    fun `without a gate a call answers immediately`() = runTest {
        val gateway = FakeCoreGateway(isAdministratorResult = true)

        assertTrue(gateway.isAdministrator())
        assertEquals(1, gateway.isAdministratorCallCount)
        assertEquals(emptyList<Any>(), gateway.listCollections())
        assertEquals(1, gateway.listCollectionsCallCount)
    }
}
