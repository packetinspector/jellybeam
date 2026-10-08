package tv.jellybeam.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.displayMessage
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import tv.jellybeam.ui.discover.distinctSeerrCards
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.PersonPage
import uniffi.jellybeam_core.SeerrCard

data class PersonUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val page: PersonPage? = null,
    /** Seerr credits beside the library; empty until (and unless) they arrive. */
    val discover: List<SeerrCard> = emptyList(),
    val discoverPending: Boolean = false,
    /** Bumped per load, so focus is placed again after every Retry even when Loading never renders. */
    val loadGeneration: Int = 0,
)

/**
 * Backs [PersonScreen]: the live person page first, then the optional Seerr row, which fails open
 * to omitted (docs/11 §Person page, docs/05 "Fail open").
 */
class PersonViewModel(private val gateway: CoreGateway, private val strings: UiStrings, private val personId: String) : ViewModel() {
    private val _state = MutableStateFlow(PersonUiState())
    val state: StateFlow<PersonUiState> = _state.asStateFlow()

    /** Declared before `init` so the first load's handle survives initialization (Retry cancels it). */
    private var load: Job? = null

    /** Drives [changeRefreshScheduler]'s sampling rate: top of the stack and resumed, same as Detail. */
    private val _active = MutableStateFlow(true)

    fun setActive(active: Boolean) {
        _active.value = active
    }

    /**
     * docs/11 §Person page: a mirror change to any shown card re-reads the shown cards, whether it
     * lands while this page is hidden or after it is back, so a played toggle whose count commits
     * late still shows. Filtered before the scheduler so conflation can't swallow a relevant event.
     * The scheduler's loop is the only reader, so two reads can never publish out of order.
     */
    private val changeRefreshScheduler: ChangeRefreshScheduler<ChangeEvent> = ChangeRefreshScheduler(
        scope = viewModelScope,
        events = gateway.changeEvents().filter(::affectsShownCards),
        active = _active.asStateFlow(),
        refresh = ::reloadShownCards,
    )

    init {
        refresh()
    }

    /** Membership never changes on a refresh, so only an upsert of a shown card, or a full refresh, matters. */
    private fun affectsShownCards(event: ChangeEvent): Boolean = when (event) {
        is ChangeEvent.Upserted -> _state.value.page?.library?.any { it.id in event.ids } == true
        ChangeEvent.Refresh -> _state.value.page?.library?.isNotEmpty() == true
        is ChangeEvent.Removed, ChangeEvent.ViewsChanged -> false
    }

    private fun refresh() {
        load?.cancel()
        _state.update { PersonUiState(loadGeneration = it.loadGeneration + 1) }
        load = viewModelScope.launch {
            val page = try {
                gateway.getPersonPage(personId)
            } catch (e: CoreException) {
                _state.update { it.copy(isLoading = false, error = e.displayMessage(strings)) }
                return@launch
            }
            // Without Seerr there is no row to wait for, so focus never holds for it.
            val tmdbPersonId = page.tmdbPersonId?.takeIf { gateway.seerrStatus().configured }
            _state.update { it.copy(isLoading = false, page = page, discoverPending = tmdbPersonId != null) }
            if (tmdbPersonId == null) return@launch
            val credits = try {
                distinctSeerrCards(gateway.personDiscoverCredits(personId, tmdbPersonId, page.libraryTmdb))
            } catch (e: CoreException) {
                emptyList()
            }
            _state.update { it.copy(discover = credits, discoverPending = false) }
        }
    }

    fun retry() = refresh()

    /**
     * Back on top after a title may have been played: asks for one read of the shown cards, for a
     * change that landed before this page existed to observe it; later changes arrive through
     * [changeRefreshScheduler], which serves both in order.
     */
    fun refreshLibrary() = changeRefreshScheduler.requestRefresh()

    /**
     * Re-reads the shown library cards from the mirror, which every played toggle and playback
     * stop updates, so their badges are current ([PersonPageLogic.refreshedLibrary]). A card the
     * mirror doesn't know, or a failure, keeps what is shown; membership and order never change.
     */
    private suspend fun reloadShownCards() {
        val shown = _state.value.page?.library ?: return
        if (shown.isEmpty()) return
        val fresh = try {
            gateway.cardsByIds(shown.map { it.id })
        } catch (_: CoreException) {
            return
        }
        _state.update { state ->
            val page = state.page ?: return@update state
            state.copy(page = page.copy(library = PersonPageLogic.refreshedLibrary(page.library, fresh)))
        }
    }
}

class PersonViewModelFactory(private val gateway: CoreGateway, private val strings: UiStrings, private val personId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PersonViewModel::class.java))
        return PersonViewModel(gateway, strings, personId) as T
    }
}
