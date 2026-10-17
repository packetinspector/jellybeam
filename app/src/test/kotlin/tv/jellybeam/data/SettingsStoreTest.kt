package tv.jellybeam.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.ui.settings.SettingsViewModel

/** docs/09: one store, one writer -- every settings change reads the newest record and persists whole. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `two concurrent updates of different fields both land in the persisted record`() = runTest {
        val seeded = defaultTestSettings()
        val gateway = FakeCoreGateway(settings = seeded)
        gateway.setSettingsGate = CompletableDeferred()
        val store = SettingsStore(gateway, backgroundScope)

        launch { store.update { it.copy(skipBackSecs = 30u) } }
        launch { store.update { it.copy(homeShelfSize = 33u) } }
        runCurrent()
        gateway.setSettingsGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            seeded.copy(skipBackSecs = 30u, homeShelfSize = 33u),
            gateway.setSettingsCalls.last(),
        )
    }

    @Test
    fun `settings reflects an update another writer made through the store`() = runTest {
        val seeded = defaultTestSettings()
        val store = SettingsStore(FakeCoreGateway(settings = seeded), backgroundScope)

        val updated = store.update { it.copy(skipBackSecs = 30u) }

        assertEquals(seeded.copy(skipBackSecs = 30u), updated)
        assertEquals(updated, store.settings.value)
    }

    /** docs/09: the store owns persisting what it published, so a caller that went away leaves nothing unwritten. */
    @Test
    fun `an update cancelled while queued behind a slower write still persists`() = runTest {
        val gateway = FakeCoreGateway(settings = defaultTestSettings())
        gateway.setSettingsGate = CompletableDeferred()
        val store = SettingsStore(gateway, backgroundScope)
        launch { store.update { it.copy(skipBackSecs = 30u) } }
        runCurrent()
        val queued = launch { store.update { it.copy(homeShelfSize = 33u) } }
        runCurrent()

        queued.cancel()
        gateway.setSettingsGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "the last write is the published record",
            store.settings.value,
            gateway.setSettingsCalls.last(),
        )
    }

    @Test
    fun `an update after a failed write persists the newest record`() = runTest {
        val gateway = FakeCoreGateway(settings = defaultTestSettings())
        val store = SettingsStore(gateway, backgroundScope)
        gateway.setSettingsError = IllegalStateException("synthetic")
        runCatching { store.update { it.copy(skipBackSecs = 30u) } }
        gateway.setSettingsError = null

        store.update { it.copy(homeShelfSize = 33u) }

        assertEquals(
            defaultTestSettings().copy(skipBackSecs = 30u, homeShelfSize = 33u),
            gateway.setSettingsCalls.last(),
        )
    }

    /** docs/21 §1.2 step 7: turning logging off writes exactly one record with only that field changed. */
    @Test
    fun `an update persists one record and leaves every other field alone`() = runTest {
        val original = defaultTestSettings().copy(diagnosticLoggingEnabled = true, homeShelfSize = 33u, skipBackSecs = 7u)
        val gateway = FakeCoreGateway(settings = original)

        SettingsStore(gateway, backgroundScope).update { it.copy(diagnosticLoggingEnabled = false) }

        assertEquals(listOf(original.copy(diagnosticLoggingEnabled = false)), gateway.setSettingsCalls)
        assertEquals(original.copy(diagnosticLoggingEnabled = false), gateway.getSettings())
    }

    @Test
    fun `logging turned off from Report stays off after a later Settings toggle`() = runTest {
        val seeded = defaultTestSettings().copy(diagnosticLoggingEnabled = true)
        val gateway = FakeCoreGateway(settings = seeded)
        val store = SettingsStore(gateway, backgroundScope)
        val viewModel = SettingsViewModel(gateway, store, ioDispatcher = Dispatchers.Main)

        store.update { it.copy(diagnosticLoggingEnabled = false) }
        viewModel.selectSkipBack(30u)
        advanceUntilIdle()

        assertEquals(
            seeded.copy(diagnosticLoggingEnabled = false, skipBackSecs = 30u),
            gateway.setSettingsCalls.last(),
        )
        assertEquals(
            "the Settings row shows logging off",
            false,
            viewModel.state.value.settings.diagnosticLoggingEnabled,
        )
    }
}
