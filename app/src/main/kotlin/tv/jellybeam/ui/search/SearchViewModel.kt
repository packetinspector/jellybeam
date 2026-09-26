package tv.jellybeam.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/** Seerr waits for a longer pause than the mirror: a network call per typing burst, never per
 * keystroke, and never ahead of the library results (docs/14 "Unified search"). */
private const val DISCOVER_DEBOUNCE_MS = 700L

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Card> = emptyList(),
    val discover: DiscoverSection = DiscoverSection.Hidden,
    /** Seerr is connected, so the empty hint names Discover too. */
    val discoverEnabled: Boolean = false,
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

    /** Seerr's own answer before [discoverSectionFor] filters it against the library results. */
    private var discoverRaw: DiscoverSection = DiscoverSection.Hidden
    private var discoverGeneration = 0
    private var discoverJob: Job? = null

    /** The last settled Seerr answer and its query, shown again at once if the text returns to it. */
    private var lastAnswer: Pair<String, DiscoverSection>? = null

    /** MainActivity's `discoverConfigured`, so a keystroke can show "searching" before the debounce
     * fires and "No matches" never flashes ahead of a pending Seerr answer. */
    private var seerrConfigured = false

    init {
        queryChanges
            .debounce(QUERY_DEBOUNCE_MS)
            .onEach { query -> dispatchSearch(query) }
            .launchIn(viewModelScope)
        queryChanges
            .debounce(DISCOVER_DEBOUNCE_MS)
            .onEach { query -> dispatchDiscover(query) }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queryChanges.value = query
        // "Searching" at once, so the section never shows another query's cards; returning to the
        // answered query restores its answer, since the query flow may not re-emit an equal value.
        val answer = lastAnswer
        when {
            !discoverQueryReady(query) -> cancelDiscover(DiscoverSection.Hidden)
            !seerrConfigured -> Unit
            answer != null && answer.first == query.trim() && answer.second is DiscoverSection.Results -> cancelDiscover(answer.second)
            else -> publishDiscover(DiscoverSection.Searching)
        }
    }

    /** Follows MainActivity's Discover connection state (docs/14 drawer gate). */
    fun setDiscoverConfigured(configured: Boolean) {
        if (configured == seerrConfigured) return
        seerrConfigured = configured
        lastAnswer = null
        _state.update { it.copy(discoverEnabled = configured) }
        if (configured) dispatchDiscover(_state.value.query) else cancelDiscover(DiscoverSection.Hidden)
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
                _state.update { it.copy(isSearching = false, results = results, discover = displayed(discoverRaw, results)) }
            }
        }
    }

    /** Seerr side of the search: its own debounce and generation guard, and any failure only ever
     * reaches the Discover section (docs/14: Seerr never affects Jellyfin browsing or search). */
    private fun dispatchDiscover(rawQuery: String) {
        // A keyboard suggestion leaves a trailing space; Seerr's answer shouldn't depend on it.
        val query = rawQuery.trim()
        if (!discoverQueryReady(query) || !seerrConfigured) {
            cancelDiscover(DiscoverSection.Hidden)
            return
        }
        val generation = ++discoverGeneration
        discoverJob?.cancel()
        // Always asks Seerr again (a failure retries, availability stays fresh), but a query whose
        // answer is already on screen refreshes in place instead of flashing "searching".
        if (lastAnswer?.first != query) publishDiscover(DiscoverSection.Searching)
        discoverJob = viewModelScope.launch {
            val answer = runCatching { gateway.seerrSearch(query, 1).cards }
                .onFailure { if (it is CancellationException) throw it }
                .fold({ DiscoverSection.Results(it) }, { DiscoverSection.Unavailable })
            if (generation != discoverGeneration) return@launch
            lastAnswer = query to answer
            publishDiscover(answer)
        }
    }

    /** Drops any in-flight Seerr answer so it can't land over [section]. */
    private fun cancelDiscover(section: DiscoverSection) {
        discoverGeneration++
        discoverJob?.cancel()
        publishDiscover(section)
    }

    private fun publishDiscover(raw: DiscoverSection) {
        discoverRaw = raw
        _state.update { it.copy(discover = displayed(raw, it.results)) }
    }

    private fun displayed(raw: DiscoverSection, library: List<Card>): DiscoverSection =
        if (raw is DiscoverSection.Results) discoverSectionFor(library, raw.cards) else raw
}

class SearchViewModelFactory(private val gateway: CoreGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SearchViewModel::class.java))
        return SearchViewModel(gateway) as T
    }
}
