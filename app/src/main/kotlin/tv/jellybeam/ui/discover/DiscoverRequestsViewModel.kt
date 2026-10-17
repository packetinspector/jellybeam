package tv.jellybeam.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.displayMessage
import tv.jellybeam.i18n.UiStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrMyRequest

data class DiscoverRequestsUiState(
    val isLoading: Boolean = true,
    val notConfigured: Boolean = false,
    val error: String? = null,
    val requests: List<SeerrMyRequest> = emptyList(),
)

/** Backs [DiscoverRequestsScreen] ("My Requests"): fetches `seerr_my_requests()` on init, fails open into inline error/retry same as every other Discover fetch. */
class DiscoverRequestsViewModel(private val gateway: CoreGateway, private val strings: UiStrings) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverRequestsUiState())
    val state: StateFlow<DiscoverRequestsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private var active = true

    init {
        refresh()
        // A change landing while this list is top (Back during the POST) reloads it; one landing while away is covered by the return.
        gateway.seerrRevision.drop(1).onEach { if (active) refresh() }.launchIn(viewModelScope)
    }

    /** Fed by the screen's `isTop`: each return to the top reloads, since a Seerr change is always made on another screen (docs/14). */
    fun setActive(isActive: Boolean) {
        if (isActive && !active) refresh()
        active = isActive
    }

    private fun refresh() {
        loadJob?.cancel()
        // Rows already on screen stay up during a reload; only an empty screen shows the skeleton (docs/14).
        _state.update { it.copy(isLoading = it.requests.isEmpty(), error = null, notConfigured = false) }
        loadJob = viewModelScope.launch {
            try {
                val requests = gateway.seerrMyRequests()
                // docs/14: the grid keys by request_id, so a row the server repeats is dropped here.
                _state.update { it.copy(isLoading = false, requests = requests.distinctBy { r -> r.requestId }) }
            } catch (e: CoreException) {
                _state.update {
                    it.copy(isLoading = false, requests = emptyList(), notConfigured = e is CoreException.SeerrNotConfigured, error = e.displayMessage(strings))
                }
            }
        }
    }

    fun retry() = refresh()
}

class DiscoverRequestsViewModelFactory(private val gateway: CoreGateway, private val strings: UiStrings) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverRequestsViewModel::class.java))
        return DiscoverRequestsViewModel(gateway, strings) as T
    }
}
