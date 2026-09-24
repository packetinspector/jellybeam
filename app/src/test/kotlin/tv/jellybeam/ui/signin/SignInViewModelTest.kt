package tv.jellybeam.ui.signin

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.FakeCoreGateway
import uniffi.jellybeam_core.AccountInfo
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.DiscoveredServer
import uniffi.jellybeam_core.QuickConnectSession

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    @Test
    fun `quick connect shows code polls every two seconds and completes once approved`() = runTest(dispatcher) {
        val gateway = QuickConnectGateway(pollResults = ArrayDeque(listOf(false, true)))
        withViewModel(gateway) { viewModel ->
            viewModel.toggleQuickConnect()
            runCurrent()

            assertTrue(viewModel.state.value.quickConnectActive)
            assertEquals("654321", viewModel.state.value.quickConnectCode)
            assertEquals(0, gateway.pollCalls)

            advanceTimeBy(1_999L)
            runCurrent()
            assertEquals(0, gateway.pollCalls)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, gateway.pollCalls)
            assertFalse(viewModel.state.value.signedIn)

            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(2, gateway.pollCalls)
            assertEquals(1, gateway.completeCalls)
            assertTrue(viewModel.state.value.signedIn)
            assertFalse(viewModel.state.value.isQuickConnecting)
        }
    }

    @Test
    fun `leaving quick connect mode cancels future polls and clears the secret state`() = runTest(dispatcher) {
        val gateway = QuickConnectGateway(pollResults = ArrayDeque(listOf(false, false)))
        withViewModel(gateway) { viewModel ->
            viewModel.toggleQuickConnect()
            runCurrent()
            assertEquals("654321", viewModel.state.value.quickConnectCode)

            viewModel.toggleQuickConnect()
            advanceTimeBy(10_000L)
            runCurrent()

            assertFalse(viewModel.state.value.quickConnectActive)
            assertNull(viewModel.state.value.quickConnectCode)
            assertEquals(0, gateway.pollCalls)
        }
    }

    @Test
    fun `disabled quick connect stops before initiation and can be retried`() = runTest(dispatcher) {
        val gateway = QuickConnectGateway(enabled = false)
        withViewModel(gateway) { viewModel ->
            viewModel.toggleQuickConnect()
            runCurrent()

            assertTrue(viewModel.state.value.quickConnectActive)
            assertFalse(viewModel.state.value.isQuickConnecting)
            assertEquals("Quick Connect is disabled on this server.", viewModel.state.value.error)
            assertEquals(0, gateway.initiateCalls)
        }
    }

    @Test
    fun `password reauthorization refreshes selected account instead of adding a server`() = runTest(dispatcher) {
        val gateway = QuickConnectGateway()
        val target = ReauthorizationTarget(2u, "http://saved.test", "user-id", "saved-name")
        withViewModel(gateway, target) { viewModel ->
            assertEquals("http://saved.test", viewModel.state.value.serverUrl)
            assertEquals("saved-name", viewModel.state.value.username)

            viewModel.onUsernameChange("renamed-user")
            viewModel.onPasswordChange("new-password")
            viewModel.signIn()
            runCurrent()

            assertEquals(Triple(2u, "renamed-user", "new-password"), gateway.reauthorizeCall)
            assertTrue(viewModel.state.value.signedIn)
        }
    }

    @Test
    fun `quick connect reauthorization uses identity-bound completion`() = runTest(dispatcher) {
        val gateway = QuickConnectGateway(pollResults = ArrayDeque(listOf(true)))
        val target = ReauthorizationTarget(1u, "http://saved.test", "user-id", "saved-name")
        withViewModel(gateway, target) { viewModel ->
            viewModel.toggleQuickConnect()
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(0, gateway.completeCalls)
            assertEquals(1u, gateway.reauthorizationCompleteIndex)
            assertTrue(viewModel.state.value.signedIn)
        }
    }

    // -- signIn failures (docs/13-feature-list.md "sign-in") ------------------

    @Test
    fun `invalid credentials on sign-in sets the plain-English error and keeps the password`() = runTest(dispatcher) {
        val fake = FakeCoreGateway(signInError = CoreException.InvalidCredentials())
        withViewModel(fake) { viewModel ->
            runCurrent()
            viewModel.onUsernameChange("viewer")
            viewModel.onPasswordChange("hunter2")

            viewModel.signIn()
            runCurrent()

            assertEquals("Wrong username or password.", viewModel.state.value.error)
            assertFalse(viewModel.state.value.isSigningIn)
            assertEquals("hunter2", viewModel.state.value.password)
        }
    }

    @Test
    fun `server unreachable on sign-in surfaces the host and reason`() = runTest(dispatcher) {
        val fake = FakeCoreGateway(signInError = CoreException.ServerUnreachable("media.example.test:8096", "connection timed out"))
        withViewModel(fake) { viewModel ->
            runCurrent()

            viewModel.signIn()
            runCurrent()

            assertEquals(
                "Couldn't reach media.example.test:8096 (connection timed out). Check the address and port, and that the server is running.",
                viewModel.state.value.error,
            )
            assertFalse(viewModel.state.value.isSigningIn)
        }
    }

    // -- LAN server autodetection (Feature B) ---------------------------------

    @Test
    fun `discovery starts on init and populates names verbatim`() = runTest(dispatcher) {
        val servers = listOf(
            DiscoveredServer(address = "http://192.0.2.10:8096", id = "server-1", name = "Living  Room ★", alreadySaved = false),
            DiscoveredServer(address = "http://192.0.2.11:8096", id = "server-2", name = "Den", alreadySaved = true),
        )
        val fake = FakeCoreGateway(discoveredServers = servers)
        val gateway = QuickConnectGateway(fake = fake)
        withViewModel(gateway) { viewModel ->
            runCurrent()

            assertEquals(servers, viewModel.state.value.discoveredServers)
            assertEquals(1, fake.discoverServersCalls)
            assertFalse(viewModel.state.value.isDiscovering)
        }
    }

    @Test
    fun `saved rows are ignored by selectDiscoveredServer`() = runTest(dispatcher) {
        val saved = DiscoveredServer(address = "http://192.0.2.12:8096", id = "server-1", name = "Saved Room", alreadySaved = true)
        val gateway = QuickConnectGateway(fake = FakeCoreGateway())
        withViewModel(gateway) { viewModel ->
            runCurrent()
            val urlBefore = viewModel.state.value.serverUrl

            viewModel.selectDiscoveredServer(saved)

            assertEquals(urlBefore, viewModel.state.value.serverUrl)
            assertFalse(viewModel.state.value.serverUrlTouched)
        }
    }

    @Test
    fun `selecting a discovered server prefills the url and triggers no sign-in call`() = runTest(dispatcher) {
        val unsaved = DiscoveredServer(address = "http://192.0.2.13:8096", id = "server-1", name = "Loft", alreadySaved = false)
        val gateway = QuickConnectGateway(fake = FakeCoreGateway())
        withViewModel(gateway) { viewModel ->
            runCurrent()

            viewModel.selectDiscoveredServer(unsaved)

            assertEquals("http://192.0.2.13:8096", viewModel.state.value.serverUrl)
            assertTrue(viewModel.state.value.serverUrlTouched)
            assertFalse(viewModel.state.value.signedIn)
            assertEquals(0, gateway.signInCalls)
        }
    }

    @Test
    fun `empty discovery result clears the loading state`() = runTest(dispatcher) {
        val gateway = QuickConnectGateway(fake = FakeCoreGateway(discoveredServers = emptyList()))
        withViewModel(gateway) { viewModel ->
            runCurrent()

            assertTrue(viewModel.state.value.discoveredServers.isEmpty())
            assertFalse(viewModel.state.value.isDiscovering)
        }
    }

    @Test
    fun `single unsaved discovery result prefills the url when untouched`() = runTest(dispatcher) {
        val unsaved = DiscoveredServer(address = "http://192.0.2.14:8096", id = "server-1", name = "Loft", alreadySaved = false)
        val gateway = QuickConnectGateway(fake = FakeCoreGateway(discoveredServers = listOf(unsaved)))
        withViewModel(gateway) { viewModel ->
            runCurrent()

            assertEquals("http://192.0.2.14:8096", viewModel.state.value.serverUrl)
            assertFalse(viewModel.state.value.serverUrlTouched)
        }
    }

    @Test
    fun `single unsaved discovery result does not override a url the user already typed`() = runTest(dispatcher) {
        val unsaved = DiscoveredServer(address = "http://192.0.2.15:8096", id = "server-1", name = "Loft", alreadySaved = false)
        val gateway = QuickConnectGateway(fake = FakeCoreGateway(discoveredServers = listOf(unsaved)))
        withViewModel(gateway) { viewModel ->
            viewModel.onServerUrlChange("http://typed.test:8096")

            runCurrent()

            assertEquals("http://typed.test:8096", viewModel.state.value.serverUrl)
        }
    }

    @Test
    fun `two discovered servers never prefill the url field`() = runTest(dispatcher) {
        val first = DiscoveredServer(address = "http://192.0.2.16:8096", id = "server-1", name = "Loft", alreadySaved = false)
        val second = DiscoveredServer(address = "http://192.0.2.17:8096", id = "server-2", name = "Den", alreadySaved = false)
        val gateway = QuickConnectGateway(fake = FakeCoreGateway(discoveredServers = listOf(first, second)))
        withViewModel(gateway) { viewModel ->
            val urlBefore = viewModel.state.value.serverUrl

            runCurrent()

            assertEquals(urlBefore, viewModel.state.value.serverUrl)
            assertFalse(viewModel.state.value.serverUrlTouched)
        }
    }

    @Test
    fun `reauthorization target skips discovery entirely`() = runTest(dispatcher) {
        val fake = FakeCoreGateway(discoveredServers = listOf(DiscoveredServer("http://192.0.2.18:8096", "server-1", "Loft", false)))
        val gateway = QuickConnectGateway(fake = fake)
        val target = ReauthorizationTarget(0u, "http://saved.test", "user-id", "saved-name")
        withViewModel(gateway, target) { viewModel ->
            runCurrent()

            assertEquals(0, fake.discoverServersCalls)
            assertTrue(viewModel.state.value.discoveredServers.isEmpty())
            assertFalse(viewModel.state.value.isDiscovering)
        }
    }

    @Test
    fun `retry discovery re-invokes the gateway`() = runTest(dispatcher) {
        val fake = FakeCoreGateway(discoveredServers = emptyList())
        val gateway = QuickConnectGateway(fake = fake)
        withViewModel(gateway) { viewModel ->
            runCurrent()
            assertEquals(1, fake.discoverServersCalls)

            viewModel.retryDiscovery()
            runCurrent()

            assertEquals(2, fake.discoverServersCalls)
        }
    }

    @Test
    fun `gateway throwing during discovery yields an empty list without surfacing an error`() = runTest(dispatcher) {
        val throwing = object : CoreGateway by FakeCoreGateway() {
            override suspend fun discoverServers(): List<DiscoveredServer> = throw RuntimeException("network unreachable")
        }
        withViewModel(throwing) { viewModel ->
            runCurrent()

            assertTrue(viewModel.state.value.discoveredServers.isEmpty())
            assertFalse(viewModel.state.value.isDiscovering)
            assertNull(viewModel.state.value.error)
        }
    }

    @Test
    fun `singleUnsavedServerToPrefill returns the only unsaved server when untouched`() {
        val server = DiscoveredServer(address = "http://192.0.2.19:8096", id = "server-1", name = "Loft", alreadySaved = false)
        assertEquals(server, singleUnsavedServerToPrefill(listOf(server), touched = false))
    }

    @Test
    fun `singleUnsavedServerToPrefill returns null once the user has typed`() {
        val server = DiscoveredServer(address = "http://192.0.2.19:8096", id = "server-1", name = "Loft", alreadySaved = false)
        assertNull(singleUnsavedServerToPrefill(listOf(server), touched = true))
    }

    @Test
    fun `singleUnsavedServerToPrefill returns null for two unsaved servers`() {
        val a = DiscoveredServer(address = "http://192.0.2.20:8096", id = "server-1", name = "Loft", alreadySaved = false)
        val b = DiscoveredServer(address = "http://192.0.2.21:8096", id = "server-2", name = "Den", alreadySaved = false)
        assertNull(singleUnsavedServerToPrefill(listOf(a, b), touched = false))
    }

    @Test
    fun `singleUnsavedServerToPrefill ignores saved servers when counting`() {
        val saved = DiscoveredServer(address = "http://192.0.2.22:8096", id = "server-1", name = "Saved", alreadySaved = true)
        val unsaved = DiscoveredServer(address = "http://192.0.2.23:8096", id = "server-2", name = "Unsaved", alreadySaved = false)
        assertEquals(unsaved, singleUnsavedServerToPrefill(listOf(saved, unsaved), touched = false))
    }

    private inline fun withViewModel(
        gateway: CoreGateway,
        target: ReauthorizationTarget? = null,
        block: (SignInViewModel) -> Unit,
    ) {
        val viewModel = SignInViewModel(gateway, target)
        try {
            block(viewModel)
        } finally {
            ViewModelStore().apply { put("sign-in", viewModel) }.clear()
        }
    }

    private class QuickConnectGateway(
        private val enabled: Boolean = true,
        private val pollResults: ArrayDeque<Boolean> = ArrayDeque(),
        /** Backing fake, exposed so a test can configure [FakeCoreGateway.discoveredServers] up front and read [FakeCoreGateway.discoverServersCalls] back. */
        val fake: FakeCoreGateway = FakeCoreGateway(),
    ) : CoreGateway by fake {
        var initiateCalls = 0
        var pollCalls = 0
        var completeCalls = 0
        var signInCalls = 0
        var reauthorizeCall: Triple<UInt, String, String>? = null
        var reauthorizationCompleteIndex: UInt? = null

        override suspend fun signIn(serverUrl: String, username: String, password: String): AccountInfo {
            signInCalls++
            throw NotImplementedError("QuickConnectGateway.signIn is not used by these discovery tests")
        }

        override suspend fun reauthorizeSession(index: UInt, username: String, password: String): AccountInfo {
            reauthorizeCall = Triple(index, username, password)
            return AccountInfo(serverUrl = "http://saved.test", userId = "user-id", userName = username)
        }

        override suspend fun quickConnectEnabled(serverUrl: String): Boolean = enabled

        override suspend fun initiateQuickConnect(serverUrl: String): QuickConnectSession {
            initiateCalls++
            return QuickConnectSession(code = "654321", secret = "opaque-secret")
        }

        override suspend fun pollQuickConnect(serverUrl: String, secret: String): Boolean {
            pollCalls++
            return pollResults.removeFirstOrNull() ?: false
        }

        override suspend fun completeQuickConnect(serverUrl: String, secret: String): AccountInfo {
            completeCalls++
            return AccountInfo(serverUrl = serverUrl, userId = "u1", userName = "viewer")
        }

        override suspend fun completeQuickConnectReauthorization(index: UInt, secret: String): AccountInfo {
            reauthorizationCompleteIndex = index
            return AccountInfo(serverUrl = "http://saved.test", userId = "user-id", userName = "saved-name")
        }
    }
}
