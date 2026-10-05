package tv.jellybeam.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.AppGraph
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.R
import tv.jellybeam.data.displayMessage
import tv.jellybeam.i18n.UiStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.DiscoveredServer

/** Prefilled per docs/05-conventions-and-servers.md -- the emulator's route to the dev server. */
private const val DEFAULT_SERVER_URL = "http://10.0.2.2:8096"

/** Non-secret identity of the saved account whose credential is being refreshed. */
data class ReauthorizationTarget(
    val index: UInt,
    val serverUrl: String,
    val userId: String,
    val userName: String,
)

data class SignInUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val username: String = "",
    val password: String = "",
    val isSigningIn: Boolean = false,
    val quickConnectActive: Boolean = false,
    val quickConnectCode: String? = null,
    val isQuickConnecting: Boolean = false,
    val signedIn: Boolean = false,
    val error: String? = null,
    /** LAN server autodetection results, verbatim (see [SignInViewModel.startDiscovery]). */
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isDiscovering: Boolean = false,
    /** Set once the user edits the server URL (typing or selecting a discovered row) -- gates the
     * single-result prefill so a slower second discovery reply never clobbers a deliberate choice.
     */
    val serverUrlTouched: Boolean = false,
)

class SignInViewModel(
    private val gateway: CoreGateway,
    private val strings: UiStrings,
    val reauthorizationTarget: ReauthorizationTarget? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SignInUiState(
            serverUrl = reauthorizationTarget?.serverUrl ?: DEFAULT_SERVER_URL,
            username = reauthorizationTarget?.userName.orEmpty(),
        ),
    )
    val state: StateFlow<SignInUiState> = _state.asStateFlow()
    private var quickConnectJob: Job? = null
    private var discoveryJob: Job? = null

    init {
        if (reauthorizationTarget == null) {
            startDiscovery()
        }
    }

    fun onServerUrlChange(value: String) {
        if (reauthorizationTarget != null) return
        if (_state.value.quickConnectActive) {
            quickConnectJob?.cancel()
        }
        _state.update {
            it.copy(
                serverUrl = value,
                serverUrlTouched = true,
                quickConnectActive = false,
                quickConnectCode = null,
                isQuickConnecting = false,
                error = null,
            )
        }
    }

    /** LAN server autodetection: a background one-shot fetch, never gating the screen's render,
     * no-op mid-reauthorization or mid-discovery, failing open to an empty list.
     */
    fun startDiscovery() {
        if (reauthorizationTarget != null) return
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            _state.update { it.copy(isDiscovering = true) }
            val servers = runCatching { gateway.discoverServers() }.getOrDefault(emptyList())
            _state.update { current ->
                val prefill = singleUnsavedServerToPrefill(servers, current.serverUrlTouched)
                current.copy(
                    discoveredServers = servers,
                    isDiscovering = false,
                    serverUrl = prefill?.address ?: current.serverUrl,
                )
            }
        }
    }

    /** The "Search again" chip -- cancels any in-flight discovery and starts a fresh one. */
    fun retryDiscovery() {
        if (reauthorizationTarget != null) return
        discoveryJob?.cancel()
        discoveryJob = null
        _state.update { it.copy(discoveredServers = emptyList()) }
        startDiscovery()
    }

    /** Fills the URL field with the row's address and marks it touched -- does not sign in. A row
     * already marked [DiscoveredServer.alreadySaved] is inert.
     */
    fun selectDiscoveredServer(server: DiscoveredServer) {
        if (server.alreadySaved) return
        _state.update {
            it.copy(
                serverUrl = server.address,
                serverUrlTouched = true,
                error = null,
            )
        }
    }

    override fun onCleared() {
        discoveryJob?.cancel()
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    /** Signs in transactionally (the core opens the candidate mirror before switching accounts). */
    fun signIn() {
        val current = _state.value
        if (current.isSigningIn) return

        _state.update { it.copy(isSigningIn = true, error = null) }
        viewModelScope.launch {
            try {
                val target = reauthorizationTarget
                if (target == null) {
                    gateway.signIn(current.serverUrl.trim(), current.username.trim(), current.password)
                } else {
                    gateway.reauthorizeSession(target.index, current.username.trim(), current.password)
                }
                _state.update { it.copy(isSigningIn = false, signedIn = true) }
            } catch (e: CoreException) {
                _state.update { it.copy(isSigningIn = false, error = e.displayMessage(strings)) }
            }
        }
    }

    /** Enters or leaves Quick Connect mode; this ViewModel owns the 2-second poll delay so leaving
     * cancels the loop without retaining native work.
     */
    fun toggleQuickConnect() {
        if (_state.value.quickConnectActive) {
            quickConnectJob?.cancel()
            quickConnectJob = null
            _state.update {
                it.copy(
                    quickConnectActive = false,
                    quickConnectCode = null,
                    isQuickConnecting = false,
                    error = null,
                )
            }
            return
        }
        startQuickConnect()
    }

    fun retryQuickConnect() {
        if (!_state.value.quickConnectActive || _state.value.isQuickConnecting) return
        startQuickConnect()
    }

    private fun startQuickConnect() {
        val serverUrl = _state.value.serverUrl.trim()
        if (serverUrl.isEmpty()) {
            _state.update { it.copy(error = strings.get(R.string.signin_error_server_address_empty)) }
            return
        }
        quickConnectJob?.cancel()
        _state.update {
            it.copy(
                quickConnectActive = true,
                quickConnectCode = null,
                isQuickConnecting = true,
                error = null,
            )
        }
        quickConnectJob = viewModelScope.launch {
            try {
                if (!gateway.quickConnectEnabled(serverUrl)) {
                    _state.update {
                        it.copy(
                            isQuickConnecting = false,
                            error = strings.get(R.string.signin_error_quick_connect_disabled),
                        )
                    }
                    return@launch
                }
                val request = gateway.initiateQuickConnect(serverUrl)
                _state.update { it.copy(quickConnectCode = request.code) }
                while (true) {
                    delay(2_000L)
                    if (gateway.pollQuickConnect(serverUrl, request.secret)) {
                        // docs/21 §2.1: Quick Connect's own auth path, distinct from
                        // CoreGateway's signIn/reauthorizeSession events.
                        val startNs = System.nanoTime()
                        val target = reauthorizationTarget
                        try {
                            if (target == null) {
                                gateway.completeQuickConnect(serverUrl, request.secret)
                            } else {
                                gateway.completeQuickConnectReauthorization(target.index, request.secret)
                            }
                            AppGraph.diag.event("auth.quickconnect") {
                                ms("ms", (System.nanoTime() - startNs) / 1_000_000)
                                tag("result", "ok")
                            }
                        } catch (e: CoreException) {
                            AppGraph.diag.event("auth.quickconnect") {
                                ms("ms", (System.nanoTime() - startNs) / 1_000_000)
                                tag("result", "error")
                            }
                            throw e
                        }
                        _state.update { it.copy(isQuickConnecting = false, signedIn = true) }
                        return@launch
                    }
                }
            } catch (e: CoreException) {
                _state.update {
                    it.copy(
                        isQuickConnecting = false,
                        error = e.displayMessage(strings),
                    )
                }
            }
        }
    }
}

/** Pure "friction rule" for [SignInViewModel.startDiscovery]: prefill the URL only when discovery
 * found exactly one unsaved server and the field is untouched -- a wrong LAN server must never be
 * one press from a password prompt.
 */
internal fun singleUnsavedServerToPrefill(servers: List<DiscoveredServer>, touched: Boolean): DiscoveredServer? {
    if (touched) return null
    return servers.filter { !it.alreadySaved }.singleOrNull()
}

class SignInViewModelFactory(
    private val gateway: CoreGateway,
    private val strings: UiStrings,
    private val reauthorizationTarget: ReauthorizationTarget? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SignInViewModel::class.java))
        return SignInViewModel(gateway, strings, reauthorizationTarget) as T
    }
}
