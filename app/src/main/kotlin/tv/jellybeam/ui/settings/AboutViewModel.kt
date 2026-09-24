package tv.jellybeam.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.ServerDetails
import uniffi.jellybeam_core.ServerInfoSnapshot
import uniffi.jellybeam_core.SyncStatus

data class AboutUiState(
    val snapshot: ServerInfoSnapshot? = null,
    val details: ServerDetails? = null,
    val detailsFailed: Boolean = false,
    val isRefreshing: Boolean = true,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val nowMs: Long = System.currentTimeMillis(),
)

/** Backs the Settings > About section (docs/13-feature-list.md "About section"): the connected
 * server's identity/mirror stats ([ServerInfoSnapshot], fail-open) plus the administrator-only
 * [ServerDetails] fetch, refreshed together on init and on every entry into the section.
 */
class AboutViewModel(private val gateway: CoreGateway) : ViewModel() {
    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** Recoverable failures keep the last good data; cancellation stops the refresh.
     * Re-read the snapshot after details persist the server's name/version (docs/13 About).
     * A call while one is in flight is dropped: section entry fires this right after init.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(isRefreshing = true) }
        refreshJob = viewModelScope.launch {
            val snapshot = runCatchingCancellable { gateway.serverInfoSnapshot() }.getOrNull()
            if (snapshot != null) _state.update { it.copy(snapshot = snapshot) }

            val syncStatus = runCatchingCancellable { gateway.syncStatus() }.getOrNull()
            if (syncStatus != null) _state.update { it.copy(syncStatus = syncStatus) }

            val detailsResult = runCatchingCancellable { gateway.fetchServerDetails() }
            _state.update { current ->
                detailsResult.fold(
                    onSuccess = { details -> current.copy(details = details, detailsFailed = false) },
                    onFailure = { current.copy(detailsFailed = true) },
                )
            }

            val refreshedSnapshot = runCatchingCancellable { gateway.serverInfoSnapshot() }.getOrNull()
            _state.update {
                it.copy(
                    snapshot = refreshedSnapshot ?: it.snapshot,
                    nowMs = System.currentTimeMillis(),
                    isRefreshing = false,
                )
            }
        }
    }
}

class AboutViewModelFactory(private val gateway: CoreGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AboutViewModel::class.java))
        return AboutViewModel(gateway) as T
    }
}
