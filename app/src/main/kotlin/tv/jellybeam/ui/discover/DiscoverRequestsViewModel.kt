package tv.jellybeam.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.displayMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
class DiscoverRequestsViewModel(private val gateway: CoreGateway) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverRequestsUiState())
    val state: StateFlow<DiscoverRequestsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        _state.update { it.copy(isLoading = true, error = null, notConfigured = false) }
        viewModelScope.launch {
            try {
                val requests = gateway.seerrMyRequests()
                // docs/14: the grid keys by request_id, so a row the server repeats is dropped here.
                _state.update { it.copy(isLoading = false, requests = requests.distinctBy { r -> r.requestId }) }
            } catch (e: CoreException) {
                _state.update {
                    it.copy(isLoading = false, notConfigured = e is CoreException.SeerrNotConfigured, error = e.displayMessage())
                }
            }
        }
    }

    fun retry() = refresh()
}

class DiscoverRequestsViewModelFactory(private val gateway: CoreGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverRequestsViewModel::class.java))
        return DiscoverRequestsViewModel(gateway) as T
    }
}
