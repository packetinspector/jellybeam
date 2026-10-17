package tv.jellybeam.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.displayMessage
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.i18n.UiStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.SeerrActiveRequest
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrGenre
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrPersonRef
import uniffi.jellybeam_core.SeerrRequestDetail
import uniffi.jellybeam_core.SeerrRequestInput
import uniffi.jellybeam_core.SeerrRequestOptions
import uniffi.jellybeam_core.SeerrSeasonStatus

/** One-shot post-mutation confirmation (docs/14): "After submit/cancel success: refresh detail
 * and show transient confirmation." Consumed (set back to `null`) by [DiscoverDetailScreen]. */
enum class DiscoverDetailTransientEvent { REQUEST_SUBMITTED, REQUEST_CANCELLED }

data class DiscoverDetailUiState(
    val isLoading: Boolean = true,
    /** Bumped with [isLoading] by every page-replacing load (Retry): a fresh focus landing (docs/15 §2). */
    val loadGeneration: Int = 0,
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
    val requestError: String? = null,
    // -- Cancel confirmation --
    val cancelDialogVisible: Boolean = false,
    /** A failed cancel's message, shown beside the card (docs/14). */
    val cancelError: String? = null,
    // -- Post-mutation confirmation --
    val transientEvent: DiscoverDetailTransientEvent? = null,
    /** One mutation (options fetch included) is in flight until its result is applied; every request, cancel and dismiss is a no-op meanwhile (docs/14). */
    val busy: Boolean = false,
)

/** What [DiscoverDetailViewModel.mutate] is running. */
private enum class MutationKind(val event: DiscoverDetailTransientEvent) {
    Request(DiscoverDetailTransientEvent.REQUEST_SUBMITTED),
    Cancel(DiscoverDetailTransientEvent.REQUEST_CANCELLED),
}

/** The fields [DiscoverDetailViewModel] takes from either a movie or a TV detail. */
private class LoadedDetail(
    val card: SeerrCard,
    val runtimeMinutes: Int?,
    val genres: List<SeerrGenre>,
    val cast: List<SeerrPersonRef>,
    val similar: List<SeerrCard>,
    val recommendations: List<SeerrCard>,
    val trailerUrl: String?,
    val criticsScore: Int?,
    val audienceScore: Int?,
    val activeRequest: SeerrActiveRequest?,
    val canRequest: Boolean,
    val canRequest4k: Boolean,
    val seasons: List<SeerrSeasonStatus>,
)

/**
 * Backs [DiscoverDetailScreen] (docs/14): a submit or cancel runs through [mutate], which `busy` admits one at a
 * time and merges into the detail on screen; a failure shows beside the card and re-reads the title. Main-confined.
 */
class DiscoverDetailViewModel(
    private val gateway: CoreGateway,
    private val strings: UiStrings,
    private val mediaType: SeerrMediaType,
    private val tmdbId: Long,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverDetailUiState())
    val state: StateFlow<DiscoverDetailUiState> = _state.asStateFlow()

    init {
        retry()
    }

    /** A page-replacing load (first open, the error page's Retry). */
    fun retry() {
        _state.update { it.copy(isLoading = true, loadGeneration = it.loadGeneration + 1, error = null, notConfigured = false) }
        viewModelScope.launch { loadDetail() }
    }

    /** Reads the title and publishes it; false when the read failed and its error is published instead. */
    private suspend fun loadDetail(): Boolean {
        try {
            val detail = if (mediaType == SeerrMediaType.MOVIE) {
                gateway.seerrMovie(tmdbId).let {
                    LoadedDetail(
                        card = it.card,
                        runtimeMinutes = it.runtimeMinutes,
                        genres = it.genres,
                        cast = it.cast,
                        similar = it.similar,
                        recommendations = it.recommendations,
                        trailerUrl = it.trailerUrl,
                        criticsScore = it.criticsScore,
                        audienceScore = it.audienceScore,
                        activeRequest = it.activeRequest,
                        canRequest = it.canRequest,
                        canRequest4k = it.canRequest4k,
                        seasons = emptyList(),
                    )
                }
            } else {
                gateway.seerrTv(tmdbId).let {
                    LoadedDetail(
                        card = it.card,
                        runtimeMinutes = null,
                        genres = it.genres,
                        cast = it.cast,
                        similar = it.similar,
                        recommendations = it.recommendations,
                        trailerUrl = it.trailerUrl,
                        criticsScore = it.criticsScore,
                        audienceScore = it.audienceScore,
                        activeRequest = it.activeRequest,
                        canRequest = it.canRequest,
                        canRequest4k = it.canRequest4k,
                        seasons = it.seasons,
                    )
                }
            }
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
                    seasons = detail.seasons,
                    seasonSelection = seasonSelectionFor(it.seasons, detail.seasons, it.seasonSelection),
                )
            }
            return true
        } catch (e: CoreException) {
            _state.update {
                it.copy(
                    isLoading = false,
                    notConfigured = e is CoreException.SeerrNotConfigured,
                    error = e.displayMessage(strings),
                )
            }
            return false
        }
    }

    /** Takes the single busy slot and clears every shown error, or returns false when a mutation is in flight. */
    private fun claim(): Boolean {
        val current = _state.value
        if (current.busy) return false
        _state.value = current.copy(busy = true, error = null, requestError = null, cancelError = null)
        return true
    }

    /** The one mutation funnel (docs/14): claims busy, then runs [perform] off the main thread's call stack. */
    private fun mutate(kind: MutationKind, op: suspend () -> SeerrRequestDetail) {
        if (!claim()) return
        viewModelScope.launch { perform(kind, op) }
    }

    /** Runs [op] under the held busy slot and releases busy last; a failure re-reads the title, else shows [DiscoverDetailUiState.error] (docs/14 Errors). */
    private suspend fun perform(kind: MutationKind, op: suspend () -> SeerrRequestDetail) {
        try {
            val detail = op()
            _state.update {
                it.withMutation(detail).dialogsClosed().copy(transientEvent = kind.event)
            }
        } catch (e: CoreException.SeerrChangeUnconfirmed) {
            _state.update { it.dialogsClosed() }
            if (loadDetail()) {
                _state.update { it.copy(transientEvent = kind.event) }
            } else {
                _state.update { it.copy(error = e.displayMessage(strings)) }
            }
        } catch (e: CoreException) {
            val message = e.displayMessage(strings)
            _state.update {
                when (kind) {
                    MutationKind.Request -> it.copy(requestError = message)
                    // The card stays; the message renders beside it, not on the card-less error screen (docs/14).
                    MutationKind.Cancel -> it.copy(cancelDialogVisible = false, cancelError = message)
                }
            }
            loadDetail()
        } finally {
            // A cleared view model publishes nothing.
            if (currentCoroutineContext().isActive) _state.update { it.copy(busy = false) }
        }
    }

    fun dismissTransientEvent() = _state.update { it.copy(transientEvent = null) }

    // -- Request flow ---------------------------------------------------

    /**
     * A movie with an empty/failed `seerr_request_options` result submits immediately with
     * defaults (docs/14); a TV title always opens the season-picker dialog instead.
     */
    fun startRequest(is4k: Boolean) {
        if (!claim()) return
        viewModelScope.launch {
            val options = runCatchingCancellable { gateway.seerrRequestOptions(mediaType, is4k) }.getOrNull()

            if (mediaType != SeerrMediaType.TV && (options == null || options.servers.isEmpty())) {
                // Movie only, empty/failed servers: submit immediately with defaults, keeping the busy slot.
                perform(MutationKind.Request, submitOp(is4k = is4k, serverId = null, profileId = null, rootFolder = null, seasons = emptyList()))
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
                    busy = false,
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

    /** Back is held while anything is in flight, so a submit's failure lands in the open dialog (docs/14). */
    fun dismissRequestDialog() = _state.update {
        if (!it.busy) it.copy(requestDialogVisible = false, requestOptions = null, requestError = null) else it
    }

    fun confirmRequestDialog() {
        val current = _state.value
        val seasons = if (mediaType == SeerrMediaType.TV) selectedRequestableSeasons(current.seasons, current.seasonSelection) else emptyList()
        mutate(
            MutationKind.Request,
            submitOp(
                is4k = current.requestIs4k,
                serverId = current.selectedServerId,
                profileId = current.selectedProfileId,
                rootFolder = current.selectedRootFolder,
                seasons = seasons,
            ),
        )
    }

    private fun submitOp(
        is4k: Boolean,
        serverId: Long?,
        profileId: Long?,
        rootFolder: String?,
        seasons: List<Int>,
    ): suspend () -> SeerrRequestDetail = {
        gateway.seerrSubmitRequestDetail(
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
    }

    // -- Cancel flow ------------------------------------------------------

    fun openCancelDialog() = _state.update { if (!it.busy) it.copy(cancelDialogVisible = true) else it }

    fun dismissCancelDialog() = _state.update { if (!it.busy) it.copy(cancelDialogVisible = false) else it }

    fun confirmCancel() {
        val requestId = _state.value.activeRequest?.requestId ?: return
        mutate(MutationKind.Cancel) { gateway.seerrCancelRequestDetail(mediaType, tmdbId, requestId) }
    }
}

class DiscoverDetailViewModelFactory(
    private val gateway: CoreGateway,
    private val strings: UiStrings,
    private val mediaType: SeerrMediaType,
    private val tmdbId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DiscoverDetailViewModel::class.java))
        return DiscoverDetailViewModel(gateway, strings, mediaType, tmdbId) as T
    }
}
