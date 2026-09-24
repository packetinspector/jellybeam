package tv.jellybeam.ui.search

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
import uniffi.jellybeam_core.Card

/** Debounce window on query changes -- shorter than the 500ms mirror-change debounce since this is
 * keystroke-driven, not a sync burst.
 */
private const val QUERY_DEBOUNCE_MS = 300L

/** `JellybeamCoreGateway.search`'s `limit` -- generous for a TV-sized results grid without pulling the
 * whole mirror over FFI on a broad query.
 */
private const val SEARCH_LIMIT = 60u

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Card> = emptyList(),
)

/**
 * Mirror-backed search: live results as the user types, debounced [QUERY_DEBOUNCE_MS] so a
 * keystroke
 * burst doesn't fire one FFI call per character. A blank query never calls [CoreGateway.search] --
 * it
 * goes straight to the empty-results state, matching [SearchScreen]'s empty-query hint contract.
 *
 * Stale-response guard: a dispatched query isn't awaited before the next debounced query can
 * dispatch
 * (see [dispatchSearch]), so two searches can be in flight with no guarantee the first dispatched
 * returns
 * first. Comparing query strings can't resolve this (a repeated identical string is
 * indistinguishable
 * from itself), so every dispatch is tagged with a monotonically increasing generation
 * ([nextGeneration]); a result is only applied if its generation is still the latest dispatched
 * ([latestGeneration]).
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SearchViewModel(private val gateway: CoreGateway) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

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

    /** Runs once per debounced query; synchronous up to launching the gateway call, so the
     * debounced collector is never blocked waiting on a slow search -- the race [latestGeneration]
     * exists to resolve.
     */
    private fun dispatchSearch(query: String) {
        val generation = ++nextGeneration
        latestGeneration = generation

        if (query.isBlank()) {
            _state.update { it.copy(isSearching = false, results = emptyList()) }
            return
        }

        _state.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            val results = gateway.search(query, SEARCH_LIMIT)
            if (generation == latestGeneration) {
                _state.update { it.copy(isSearching = false, results = results) }
            }
        }
    }
}

class SearchViewModelFactory(private val gateway: CoreGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SearchViewModel::class.java))
        return SearchViewModel(gateway) as T
    }
}
