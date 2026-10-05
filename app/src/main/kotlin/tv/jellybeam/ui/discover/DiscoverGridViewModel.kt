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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrBrowseFilters
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrGenre
import uniffi.jellybeam_core.SeerrMediaType

/** docs/14-seerr-discover.md: only the two library-browse kinds show a sort/genre-filter row. */
fun SeerrBrowseKind.supportsSortAndFilter(): Boolean =
    this == SeerrBrowseKind.MOVIES || this == SeerrBrowseKind.TV

/** Maps onto [SeerrMediaType] for the genre-picker's `seerr_genres(mediaType)` call; other kinds
 * never call it (see [supportsSortAndFilter]).
 */
fun SeerrBrowseKind.toMediaType(): SeerrMediaType? = when (this) {
    SeerrBrowseKind.MOVIES -> SeerrMediaType.MOVIE
    SeerrBrowseKind.TV -> SeerrMediaType.TV
    else -> null
}

data class DiscoverGridUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val notConfigured: Boolean = false,
    val error: String? = null,
    val cards: List<SeerrCard> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val sort: DiscoverSortOption = DiscoverSortOption.POPULARITY,
    val genres: List<SeerrGenre> = emptyList(),
    val selectedGenre: SeerrGenre? = null,
)

/**
 * Backs [DiscoverGridScreen]: fetches page 1 on init, pages forward near the scroll end
 * ([shouldFetchNextDiscoverPage]), and refetches page 1 on sort/genre change (docs/14).
 * Generation-guarded
 * against stale responses -- a rapid sort-chip flip could otherwise let an older page-1 fetch land
 * after a
 * newer one.
 */
class DiscoverGridViewModel(
    private val gateway: CoreGateway,
    private val strings: UiStrings,
    private val kind: SeerrBrowseKind,
    /** Pre-selects a genre (opened from a genre link), seeded into the first fetch rather than a
     * follow-up [selectGenre] call so opening pre-filtered never double-fetches page 1.
     */
    initialGenre: SeerrGenre? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverGridUiState(selectedGenre = initialGenre))
    val state: StateFlow<DiscoverGridUiState> = _state.asStateFlow()

    private var nextGeneration = 0
    private var latestGeneration = 0

    init {
        if (kind.supportsSortAndFilter()) {
            viewModelScope.launch {
                val mediaType = kind.toMediaType() ?: return@launch
                val genres = runCatching { gateway.seerrGenres(mediaType) }.getOrDefault(emptyList())
                _state.update { it.copy(genres = genres) }
            }
        }
        fetchFirstPage()
    }

    private fun filters(): SeerrBrowseFilters {
        val state = _state.value
        return SeerrBrowseFilters(
            sortBy = if (kind.supportsSortAndFilter()) state.sort.sortByValue(kind.toMediaType() ?: SeerrMediaType.MOVIE) else null,
            genreId = state.selectedGenre?.id,
            minVote = null,
            networkId = null,
            status = null,
        )
    }

    private fun fetchFirstPage() {
        val generation = ++nextGeneration
        latestGeneration = generation
        pagingBudget = PagingBudget.Counting(0)
        // isLoadingMore is cleared here, not by the superseded next-page coroutine: that one
        // returns on its stale generation without touching state, and would otherwise leave
        // the new listing unable to page.
        _state.update { it.copy(isLoading = true, isLoadingMore = false, error = null, notConfigured = false) }
        viewModelScope.launch {
            try {
                val page = gateway.seerrBrowse(kind, 1, filters())
                if (generation != latestGeneration) return@launch
                _state.update {
                    it.copy(
                        isLoading = false,
                        cards = distinctSeerrCards(page.cards),
                        // The cursor is ours from page 1 on; the response's page number is never read.
                        page = 1,
                        hasMore = page.totalPages > 1,
                    )
                }
            } catch (e: CoreException) {
                if (generation != latestGeneration) return@launch
                _state.update {
                    it.copy(
                        isLoading = false,
                        notConfigured = e is CoreException.SeerrNotConfigured,
                        error = e.displayMessage(strings),
                    )
                }
            }
        }
    }

    fun retry() = fetchFirstPage()

    fun selectSort(sort: DiscoverSortOption) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        fetchFirstPage()
    }

    fun selectGenre(genre: SeerrGenre?) {
        if (genre == _state.value.selectedGenre) return
        _state.update { it.copy(selectedGenre = genre) }
        fetchFirstPage()
    }

    /** docs/14: the automatic no-growth chain is either counting toward its pause or paused at
     * the trigger index that hit it. */
    private sealed interface PagingBudget {
        data class Counting(val emptyPages: Int) : PagingBudget
        data class Paused(val atIndex: Int) : PagingBudget
    }

    private var pagingBudget: PagingBudget = PagingBudget.Counting(0)

    /**
     * One page per call: a page that adds no new card still advances [DiscoverGridUiState.page],
     * and the screen's loader effect, keyed on it, calls back in (docs/14 "Card lists are
     * de-duplicated"). `hasMore` is the server's word, never inferred from repeats.
     * [MAX_CONSECUTIVE_EMPTY_PAGES] no-growth pages pause the chain until a trigger from a
     * different [lastVisibleIndex] re-arms it; the short-grid residual is docs/14's.
     */
    fun loadNextPageIfNeeded(lastVisibleIndex: Int, columns: Int) {
        val current = _state.value
        if (current.isLoadingMore || current.isLoading) return
        if (!shouldFetchNextDiscoverPage(lastVisibleIndex, current.cards.size, columns, current.hasMore)) return
        val paused = pagingBudget as? PagingBudget.Paused
        if (paused != null) {
            if (paused.atIndex == lastVisibleIndex) return
            pagingBudget = PagingBudget.Counting(0)
        }

        val generation = latestGeneration
        val nextPage = current.page + 1
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val result = runCatching { gateway.seerrBrowse(kind, nextPage, filters()) }
            if (generation != latestGeneration) return@launch
            result.onSuccess { page ->
                val before = _state.value.cards.size
                val merged = distinctSeerrCards(_state.value.cards + page.cards)
                // The cursor is ours: a response's page number never rewinds or stalls it.
                _state.update {
                    it.copy(isLoadingMore = false, cards = merged, page = nextPage, hasMore = nextPage < page.totalPages)
                }
                val emptyPages = if (merged.size > before) 0 else ((pagingBudget as? PagingBudget.Counting)?.emptyPages ?: 0) + 1
                pagingBudget = if (emptyPages >= MAX_CONSECUTIVE_EMPTY_PAGES) PagingBudget.Paused(lastVisibleIndex) else PagingBudget.Counting(emptyPages)
            }.onFailure {
                // Fail open: stop paging silently, page already on screen is undisturbed (docs/14).
                _state.update { it.copy(isLoadingMore = false, hasMore = false) }
            }
        }
    }

    private companion object {
        /** No-growth pages in a row before automatic paging pauses for a user scroll (docs/14):
         * eight is 160 server-default items of pure repeats. */
        const val MAX_CONSECUTIVE_EMPTY_PAGES = 8
    }
}

class DiscoverGridViewModelFactory(
    private val gateway: CoreGateway,
    private val strings: UiStrings,
    private val kind: SeerrBrowseKind,
    private val initialGenre: SeerrGenre? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverGridViewModel::class.java))
        return DiscoverGridViewModel(gateway, strings, kind, initialGenre) as T
    }
}
