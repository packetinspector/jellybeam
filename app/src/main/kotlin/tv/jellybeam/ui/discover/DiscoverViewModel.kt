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
import uniffi.jellybeam_core.SeerrHomeRow

data class DiscoverUiState(
    val isLoading: Boolean = true,
    /** [uniffi.jellybeam_core.CoreException.SeerrNotConfigured] specifically -- routes the message
     * toward Settings rather than a generic retry (docs/14).
     */
    val notConfigured: Boolean = false,
    val error: String? = null,
    val rows: List<SeerrHomeRow> = emptyList(),
)

/** Backs [DiscoverScreen] (docs/14-seerr-discover.md): fetches `seerr_home()` on init, not app
 * startup. Fails open into this screen's inline error text plus Retry.
 */
class DiscoverViewModel(private val gateway: CoreGateway) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        _state.update { it.copy(isLoading = true, error = null, notConfigured = false) }
        viewModelScope.launch {
            try {
                val home = gateway.seerrHome()
                _state.update { it.copy(isLoading = false, rows = home.rows.map { row -> row.copy(cards = distinctSeerrCards(row.cards)) }) }
            } catch (e: CoreException) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        notConfigured = e is CoreException.SeerrNotConfigured,
                        error = e.displayMessage(),
                    )
                }
            }
        }
    }

    fun retry() = refresh()
}

class DiscoverViewModelFactory(private val gateway: CoreGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverViewModel::class.java))
        return DiscoverViewModel(gateway) as T
    }
}
