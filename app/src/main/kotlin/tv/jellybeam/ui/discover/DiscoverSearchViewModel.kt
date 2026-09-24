package tv.jellybeam.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.SeerrCard

/** Same debounce window as [tv.jellybeam.ui.search.SearchViewModel]. */
private const val QUERY_DEBOUNCE_MS = 300L

data class DiscoverSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<SeerrCard> = emptyList(),
)

/** [tv.jellybeam.ui.search.SearchViewModel]'s recipe (debounce + generation-guard), querying
 * `seerr_search` instead of the local mirror. Page 1 only -- docs/14 calls append-on-scroll here
 * optional.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class DiscoverSearchViewModel(private val gateway: CoreGateway) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverSearchUiState())
    val state: StateFlow<DiscoverSearchUiState> = _state.asStateFlow()

    private val queryChanges = MutableStateFlow("")
    private var nextGeneration = 0
    private var latestGeneration = 0

    init {
        queryChanges
            .debounce(QUERY_DEBOUNCE_MS)
            .onEach { query -> dispatchSearch(query) }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queryChanges.value = query
    }

    private fun dispatchSearch(query: String) {
        val generation = ++nextGeneration
        latestGeneration = generation

        if (query.isBlank()) {
            _state.update { it.copy(isSearching = false, results = emptyList()) }
            return
        }

        _state.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            val results = distinctSeerrCards(runCatching { gateway.seerrSearch(query, 1) }.getOrNull()?.cards.orEmpty())
            if (generation == latestGeneration) {
                _state.update { it.copy(isSearching = false, results = results) }
            }
        }
    }
}

class DiscoverSearchViewModelFactory(private val gateway: CoreGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverSearchViewModel::class.java))
        return DiscoverSearchViewModel(gateway) as T
    }
}
