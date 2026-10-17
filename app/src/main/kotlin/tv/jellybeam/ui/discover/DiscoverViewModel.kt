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
class DiscoverViewModel(private val gateway: CoreGateway, private val strings: UiStrings) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

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
        _state.update { it.copy(isLoading = it.rows.isEmpty(), error = null, notConfigured = false) }
        loadJob = viewModelScope.launch {
            try {
                val home = gateway.seerrHome()
                _state.update { it.copy(isLoading = false, rows = home.rows.map { row -> row.copy(cards = distinctSeerrCards(row.cards)) }) }
            } catch (e: CoreException) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        rows = emptyList(),
                        notConfigured = e is CoreException.SeerrNotConfigured,
                        error = e.displayMessage(strings),
                    )
                }
            }
        }
    }

    fun retry() = refresh()
}

class DiscoverViewModelFactory(private val gateway: CoreGateway, private val strings: UiStrings) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverViewModel::class.java))
        return DiscoverViewModel(gateway, strings) as T
    }
}
