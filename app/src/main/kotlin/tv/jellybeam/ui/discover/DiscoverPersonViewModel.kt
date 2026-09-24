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
import uniffi.jellybeam_core.SeerrCard

data class DiscoverPersonUiState(
    val isLoading: Boolean = true,
    val notConfigured: Boolean = false,
    val error: String? = null,
    val name: String = "",
    val profileUrl: String? = null,
    val credits: List<SeerrCard> = emptyList(),
)

/** Backs [DiscoverPersonScreen]: fetches `seerr_person(personId)` on init, fails open into inline error/retry same as every other Discover fetch. */
class DiscoverPersonViewModel(private val gateway: CoreGateway, private val personId: Long) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverPersonUiState())
    val state: StateFlow<DiscoverPersonUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        _state.update { it.copy(isLoading = true, error = null, notConfigured = false) }
        viewModelScope.launch {
            try {
                val credits = gateway.seerrPerson(personId)
                _state.update {
                    it.copy(isLoading = false, name = credits.name, profileUrl = credits.profileUrl, credits = distinctSeerrCards(credits.credits))
                }
            } catch (e: CoreException) {
                _state.update {
                    it.copy(isLoading = false, notConfigured = e is CoreException.SeerrNotConfigured, error = e.displayMessage())
                }
            }
        }
    }

    fun retry() = refresh()
}

class DiscoverPersonViewModelFactory(private val gateway: CoreGateway, private val personId: Long) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverPersonViewModel::class.java))
        return DiscoverPersonViewModel(gateway, personId) as T
    }
}
