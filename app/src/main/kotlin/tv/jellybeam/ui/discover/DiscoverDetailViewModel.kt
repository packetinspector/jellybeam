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
import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrGenre
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrPersonRef
import uniffi.jellybeam_core.SeerrRequestInput
import uniffi.jellybeam_core.SeerrRequestOptions
import uniffi.jellybeam_core.SeerrSeasonStatus

/** One-shot post-mutation confirmation (docs/14): "After submit/cancel success: refresh detail
 * and show transient confirmation." Consumed (set back to `null`) by [DiscoverDetailScreen]. */
enum class DiscoverDetailTransientEvent { REQUEST_SUBMITTED, REQUEST_CANCELLED }

data class DiscoverDetailUiState(
    val isLoading: Boolean = true,
    val notConfigured: Boolean = false,
    val error: String? = null,
    val card: SeerrCard? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<SeerrGenre> = emptyList(),
    val cast: List<SeerrPersonRef> = emptyList(),
    val similar: List<SeerrCard> = emptyList(),
    val recommendations: List<SeerrCard> = emptyList(),
    val trailerUrl: String? = null,
    val criticsScore: Int? = null,
    val audienceScore: Int? = null,
    val activeRequest: SeerrActiveRequest? = null,
    val canRequest: Boolean = false,
    val canRequest4k: Boolean = false,
    /** TV only -- always empty for a movie (docs/14: the FFI's own single SD-only season list). */
    val seasons: List<SeerrSeasonStatus> = emptyList(),
    // -- Request dialog (only shown when seerr_request_options returns servers) --
    val requestOptions: SeerrRequestOptions? = null,
    val requestDialogVisible: Boolean = false,
    val requestIs4k: Boolean = false,
    val seasonSelection: SeasonSelection = emptyMap(),
    val selectedServerId: Long? = null,
    val selectedProfileId: Long? = null,
    val selectedRootFolder: String? = null,
    val isStartingRequest: Boolean = false,
    val isSubmittingRequest: Boolean = false,
    val requestError: String? = null,
    // -- Cancel confirmation --
    val cancelDialogVisible: Boolean = false,
    val isCancelling: Boolean = false,
    // -- Post-mutation confirmation --
    val transientEvent: DiscoverDetailTransientEvent? = null,
)

/**
 * Backs [DiscoverDetailScreen] (docs/14-seerr-discover.md): fetches `seerr_movie`/`seerr_tv` by
 * [mediaType] on init. Request flow (see [startRequest]): a movie with an empty/failed
 * `seerr_request_options` result submits immediately with defaults; a TV title always opens the
 * season-picker dialog. A successful submit or cancel re-fetches the whole detail and sets
 * [DiscoverDetailTransientEvent] for a one-shot confirmation.
 */
class DiscoverDetailViewModel(
    private val gateway: CoreGateway,
    private val mediaType: SeerrMediaType,
    private val tmdbId: Long,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverDetailUiState())
    val state: StateFlow<DiscoverDetailUiState> = _state.asStateFlow()

    init {
        refresh(showLoading = true)
    }

    private fun refresh(showLoading: Boolean) {
        if (showLoading) _state.update { it.copy(isLoading = true, error = null, notConfigured = false) }
        viewModelScope.launch { loadDetail() }
    }

    private suspend fun loadDetail() {
        try {
            if (mediaType == SeerrMediaType.MOVIE) {
                val detail = gateway.seerrMovie(tmdbId)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        card = detail.card,
                        runtimeMinutes = detail.runtimeMinutes,
                        genres = detail.genres,
                        cast = detail.cast,
                        similar = distinctSeerrCards(detail.similar),
                        recommendations = distinctSeerrCards(detail.recommendations),
                        trailerUrl = detail.trailerUrl,
                        criticsScore = detail.criticsScore,
                        audienceScore = detail.audienceScore,
                        activeRequest = detail.activeRequest,
                        canRequest = detail.canRequest,
                        canRequest4k = detail.canRequest4k,
                        seasons = emptyList(),
                    )
                }
            } else {
                val detail = gateway.seerrTv(tmdbId)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        card = detail.card,
                        runtimeMinutes = null,
                        genres = detail.genres,
                        cast = detail.cast,
                        similar = distinctSeerrCards(detail.similar),
                        recommendations = distinctSeerrCards(detail.recommendations),
                        trailerUrl = detail.trailerUrl,
                        criticsScore = detail.criticsScore,
                        audienceScore = detail.audienceScore,
                        activeRequest = detail.activeRequest,
                        canRequest = detail.canRequest,
                        canRequest4k = detail.canRequest4k,
                        seasons = detail.seasons,
                        seasonSelection = initialSeasonSelection(detail.seasons),
                    )
                }
            }
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

    fun retry() = refresh(showLoading = true)

    fun dismissTransientEvent() = _state.update { it.copy(transientEvent = null) }

    // -- Request flow ---------------------------------------------------

    /**
     * A movie with an empty/failed `seerr_request_options` result submits immediately with
     * defaults (docs/14); a TV title always opens the season-picker dialog instead.
     */
    fun startRequest(is4k: Boolean) {
        _state.update { it.copy(isStartingRequest = true, requestError = null) }
        viewModelScope.launch {
            val options = runCatching { gateway.seerrRequestOptions(mediaType, is4k) }.getOrNull()
            _state.update { it.copy(isStartingRequest = false) }

            if (mediaType != SeerrMediaType.TV && (options == null || options.servers.isEmpty())) {
                // Movie only, empty/failed servers: submit immediately with defaults.
                submitRequest(is4k = is4k, serverId = null, profileId = null, rootFolder = null, seasons = emptyList())
                return@launch
            }

            // Stored null so "no options" means one thing whether the fetch failed or came back
            // empty.
            val usableOptions = options?.takeIf { it.servers.isNotEmpty() }
            val defaultServer = usableOptions?.servers?.firstOrNull { it.isDefault } ?: usableOptions?.servers?.firstOrNull()
            val defaultProfile = defaultServer?.profiles?.firstOrNull { it.isDefault } ?: defaultServer?.profiles?.firstOrNull()
            val defaultFolder = defaultServer?.rootFolders?.firstOrNull { it.isDefault } ?: defaultServer?.rootFolders?.firstOrNull()
            _state.update {
                it.copy(
                    requestOptions = usableOptions,
                    requestDialogVisible = true,
                    requestIs4k = is4k,
                    selectedServerId = defaultServer?.serverId,
                    selectedProfileId = defaultProfile?.id,
                    selectedRootFolder = defaultFolder?.path,
                    seasonSelection = initialSeasonSelection(it.seasons),
                )
            }
        }
    }

    fun selectServer(serverId: Long) {
        val server = _state.value.requestOptions?.servers?.firstOrNull { it.serverId == serverId } ?: return
        val profile = server.profiles.firstOrNull { it.isDefault } ?: server.profiles.firstOrNull()
        val folder = server.rootFolders.firstOrNull { it.isDefault } ?: server.rootFolders.firstOrNull()
        _state.update { it.copy(selectedServerId = serverId, selectedProfileId = profile?.id, selectedRootFolder = folder?.path) }
    }

    fun selectProfile(profileId: Long) = _state.update { it.copy(selectedProfileId = profileId) }

    fun selectRootFolder(path: String) = _state.update { it.copy(selectedRootFolder = path) }

    fun toggleSeason(season: SeerrSeasonStatus) =
        _state.update { it.copy(seasonSelection = toggleSeasonSelection(it.seasonSelection, season)) }

    fun toggleSelectAllSeasons() =
        _state.update { it.copy(seasonSelection = toggleSelectAllRequestableSeasons(it.seasons, it.seasonSelection)) }

    fun dismissRequestDialog() = _state.update {
        it.copy(requestDialogVisible = false, requestOptions = null, requestError = null)
    }

    fun confirmRequestDialog() {
        val current = _state.value
        val seasons = if (mediaType == SeerrMediaType.TV) selectedRequestableSeasons(current.seasons, current.seasonSelection) else emptyList()
        submitRequest(
            is4k = current.requestIs4k,
            serverId = current.selectedServerId,
            profileId = current.selectedProfileId,
            rootFolder = current.selectedRootFolder,
            seasons = seasons,
        )
    }

    private fun submitRequest(is4k: Boolean, serverId: Long?, profileId: Long?, rootFolder: String?, seasons: List<Int>) {
        _state.update { it.copy(isSubmittingRequest = true, requestError = null) }
        viewModelScope.launch {
            try {
                gateway.seerrSubmitRequest(
                    SeerrRequestInput(
                        mediaType = mediaType,
                        tmdbId = tmdbId,
                        is4k = is4k,
                        seasons = seasons,
                        serverId = serverId,
                        profileId = profileId,
                        rootFolder = rootFolder,
                    ),
                )
                _state.update {
                    it.copy(isSubmittingRequest = false, requestDialogVisible = false, requestOptions = null)
                }
                loadDetail()
                _state.update { it.copy(transientEvent = DiscoverDetailTransientEvent.REQUEST_SUBMITTED) }
            } catch (e: CoreException) {
                _state.update { it.copy(isSubmittingRequest = false, requestError = e.displayMessage()) }
            }
        }
    }

    // -- Cancel flow ------------------------------------------------------

    fun openCancelDialog() = _state.update { it.copy(cancelDialogVisible = true) }

    fun dismissCancelDialog() = _state.update { it.copy(cancelDialogVisible = false) }

    fun confirmCancel() {
        val requestId = _state.value.activeRequest?.requestId ?: return
        _state.update { it.copy(isCancelling = true) }
        viewModelScope.launch {
            try {
                gateway.seerrCancelRequest(requestId)
                _state.update { it.copy(isCancelling = false, cancelDialogVisible = false) }
                loadDetail()
                _state.update { it.copy(transientEvent = DiscoverDetailTransientEvent.REQUEST_CANCELLED) }
            } catch (e: CoreException) {
                _state.update { it.copy(isCancelling = false, cancelDialogVisible = false, error = e.displayMessage()) }
            }
        }
    }
}

class DiscoverDetailViewModelFactory(
    private val gateway: CoreGateway,
    private val mediaType: SeerrMediaType,
    private val tmdbId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverDetailViewModel::class.java))
        return DiscoverDetailViewModel(gateway, mediaType, tmdbId) as T
    }
}
