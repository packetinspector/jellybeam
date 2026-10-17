package tv.jellybeam.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.displayMessage
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.i18n.UiStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrAuthMethod
import uniffi.jellybeam_core.SeerrStatus

/** [SeerrStatus] equivalent of "nothing saved yet" -- this file's default, not persisted anywhere.
 */
private fun notConfiguredStatus(): SeerrStatus = SeerrStatus(configured = false, seerrUrl = null, method = null, identity = null, appTitle = null)

data class DiscoverSettingsUiState(
    val isLoading: Boolean = true,
    val status: SeerrStatus = notConfiguredStatus(),
    val method: SeerrAuthMethod = SeerrAuthMethod.JELLYFIN,
    val url: String = "",
    val identity: String = "",
    val secret: String = "",
    val isConnecting: Boolean = false,
    val error: String? = null,
    val disconnectConfirmVisible: Boolean = false,
)

/** Backs the Settings > Discover section (docs/14-seerr-discover.md): reads `seerr_status()` on
 * init, then writes through only on an explicit Connect/Disconnect press -- unlike
 * [SettingsViewModel]'s `Settings` record, Seerr's config isn't part of that whole-record store.
 */
class DiscoverSettingsViewModel(private val gateway: CoreGateway, private val strings: UiStrings) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverSettingsUiState())
    val state: StateFlow<DiscoverSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val status = runCatchingCancellable { gateway.seerrStatus() }.getOrElse { notConfiguredStatus() }
            _state.update {
                it.copy(
                    isLoading = false,
                    status = status,
                    method = status.method ?: it.method,
                    url = status.seerrUrl ?: it.url,
                    identity = status.identity ?: it.identity,
                )
            }
        }
    }

    fun onMethodChange(method: SeerrAuthMethod) = _state.update { it.copy(method = method, error = null) }
    fun onUrlChange(url: String) = _state.update { it.copy(url = url, error = null) }
    fun onIdentityChange(identity: String) = _state.update { it.copy(identity = identity, error = null) }
    fun onSecretChange(secret: String) = _state.update { it.copy(secret = secret, error = null) }

    /** The gateway's `seerrRevision` bump (not a callback) shows the drawer's "Discover" entry (docs/14). */
    fun connect() {
        val current = _state.value
        _state.update { it.copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            try {
                val status = gateway.seerrConnect(current.url.trim(), current.method, current.identity.trim(), current.secret)
                _state.update { it.copy(isConnecting = false, status = status, secret = "", error = null) }
            } catch (e: CoreException) {
                _state.update { it.copy(isConnecting = false, error = e.displayMessage(strings)) }
            }
        }
    }

    fun openDisconnectConfirm() = _state.update { it.copy(disconnectConfirmVisible = true) }
    fun dismissDisconnectConfirm() = _state.update { it.copy(disconnectConfirmVisible = false) }

    /** Best-effort like `JellybeamCore::seerr_disconnect` itself: never throws. */
    fun disconnect() {
        viewModelScope.launch {
            gateway.seerrDisconnect()
            _state.update {
                DiscoverSettingsUiState(isLoading = false, status = notConfiguredStatus())
            }
        }
    }
}

class DiscoverSettingsViewModelFactory(private val gateway: CoreGateway, private val strings: UiStrings) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverSettingsViewModel::class.java))
        return DiscoverSettingsViewModel(gateway, strings) as T
    }
}
