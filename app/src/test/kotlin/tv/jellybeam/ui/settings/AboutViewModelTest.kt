package tv.jellybeam.ui.settings

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import tv.jellybeam.data.emptyServerInfoSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.ServerDetails
import uniffi.jellybeam_core.ServerInfoSnapshot
import uniffi.jellybeam_core.SyncStatus

private fun sampleDetails(): ServerDetails = ServerDetails(
    serverName = "Living Room",
    version = "10.11.0",
    productName = "Jellyfin Server",
    operatingSystem = "Linux",
    architecture = "X64",
    hasPendingRestart = false,
    hasUpdateAvailable = false,
    systemInfoAvailable = true,
)

class AboutViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `clearing during any refresh stage stops further calls and state updates`() = runTest {
        for (cancelAt in 1..4) {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            suspend fun step() {
                calls++
                if (calls == cancelAt) gate.await()
            }
            val gateway = object : CoreGateway by FakeCoreGateway() {
                override suspend fun serverInfoSnapshot(): ServerInfoSnapshot {
                    step()
                    return emptyServerInfoSnapshot()
                }

                override suspend fun syncStatus(): SyncStatus {
                    step()
                    return SyncStatus.Idle
                }

                override suspend fun fetchServerDetails(): ServerDetails {
                    step()
                    return sampleDetails()
                }
            }
            val viewModel = AboutViewModel(gateway)
            val store = ViewModelStore().apply { put("about", viewModel) }
            try {
                assertEquals(cancelAt, calls)
                val beforeClear = viewModel.state.value
                store.clear()
                assertEquals("Calls after cancellation at stage $cancelAt", cancelAt, calls)
                assertEquals(beforeClear, viewModel.state.value)
            } finally {
                store.clear()
                gate.cancel()
            }
        }
    }

    @Test
    fun `a refresh during one in flight is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var snapshotCalls = 0
        val gateway = object : CoreGateway by FakeCoreGateway() {
            override suspend fun serverInfoSnapshot(): ServerInfoSnapshot {
                snapshotCalls++
                gate.await()
                return emptyServerInfoSnapshot()
            }
        }
        val viewModel = AboutViewModel(gateway)
        val store = ViewModelStore().apply { put("about", viewModel) }
        try {
            viewModel.refresh()

            assertEquals(1, snapshotCalls)
        } finally {
            store.clear()
            gate.cancel()
        }
    }

    @Test
    fun `failed refresh preserves previously loaded details`() = runTest {
        val gateway = FakeCoreGateway().apply { serverDetailsValue = sampleDetails() }
        val viewModel = AboutViewModel(gateway)
        gateway.serverDetailsValue = null

        viewModel.refresh()

        assertEquals(sampleDetails(), viewModel.state.value.details)
        assertTrue(viewModel.state.value.detailsFailed)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `init loads the snapshot then the server details`() = runTest {
        val gateway = FakeCoreGateway()
        gateway.serverInfoSnapshotValue = emptyServerInfoSnapshot().copy(serverUrl = "https://jellyfin.example.test")
        gateway.serverDetailsValue = sampleDetails()

        val viewModel = AboutViewModel(gateway)

        assertFalse(viewModel.state.value.isRefreshing)
        assertEquals("https://jellyfin.example.test", viewModel.state.value.snapshot?.serverUrl)
        assertEquals(sampleDetails(), viewModel.state.value.details)
        assertFalse(viewModel.state.value.detailsFailed)
    }

    @Test
    fun `a details failure keeps the snapshot and flags detailsFailed`() = runTest {
        val gateway = FakeCoreGateway()
        gateway.serverInfoSnapshotValue = emptyServerInfoSnapshot().copy(serverUrl = "https://jellyfin.example.test")
        gateway.serverDetailsValue = null // unmapped -> throws CoreException.NotSignedIn

        val viewModel = AboutViewModel(gateway)

        assertEquals("https://jellyfin.example.test", viewModel.state.value.snapshot?.serverUrl)
        assertTrue(viewModel.state.value.detailsFailed)
        assertNull(viewModel.state.value.details)
    }

    @Test
    fun `refresh re-reads the snapshot after the details fetch`() = runTest {
        val gateway = FakeCoreGateway()
        gateway.serverInfoSnapshotValue = emptyServerInfoSnapshot().copy(
            serverUrl = "https://jellyfin.example.test",
            serverVersion = null,
        )
        gateway.serverDetailsValue = sampleDetails()
        gateway.onServerInfoSnapshotCall = {
            if (gateway.serverInfoSnapshotCalls == 1) {
                gateway.serverInfoSnapshotValue = gateway.serverInfoSnapshotValue.copy(serverVersion = "10.11.0")
            }
        }

        val viewModel = AboutViewModel(gateway)

        assertEquals(2, gateway.serverInfoSnapshotCalls)
        assertEquals("10.11.0", viewModel.state.value.snapshot?.serverVersion)
    }

    @Test
    fun `a NotSignedIn snapshot renders the empty state`() = runTest {
        val gateway = FakeCoreGateway() // defaults: no session, serverDetailsValue null

        val viewModel = AboutViewModel(gateway)

        assertNull(viewModel.state.value.snapshot?.serverUrl)
        assertTrue(viewModel.state.value.detailsFailed)
        assertNull(viewModel.state.value.details)
    }
}
