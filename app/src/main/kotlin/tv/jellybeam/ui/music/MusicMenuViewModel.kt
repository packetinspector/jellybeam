package tv.jellybeam.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import uniffi.jellybeam_core.Card

/** docs/29 §9 L1/E2/E3: the open menu's levels; All lists every playlist, the one level that scrolls. */
internal enum class MusicMenuLevel { First, Playlists, AllPlaylists, NewPlaylist }

/** A playlist level's rows: [holding] names the playlists that already hold the song. */
internal sealed interface PlaylistsLoad {
    data object Loading : PlaylistsLoad
    data class Ready(val playlists: List<Card>, val holding: Set<String> = emptySet()) : PlaylistsLoad
    data object Failed : PlaylistsLoad
}

/** [target] `null` is a closed menu; [isFavorite] is the row target's flag as the panel shows it; [returnKey] takes focus back on close. */
internal data class MusicMenuState(
    val target: MusicMenuTarget? = null,
    val returnKey: String? = null,
    val level: MusicMenuLevel = MusicMenuLevel.First,
    val isFavorite: Boolean = false,
    val isAdministrator: Boolean = false,
    val recent: PlaylistsLoad = PlaylistsLoad.Loading,
    val all: PlaylistsLoad = PlaylistsLoad.Loading,
)

/** What the bar confirms after a playlist write (docs/29 §8.4). */
internal sealed interface PlaylistOutcome {
    data class Added(val playlist: String, val again: Boolean) : PlaylistOutcome
    data class Failed(val playlist: String) : PlaylistOutcome
}

/**
 * docs/29 §9 L/E2/E3: one screen's ··· menu. Reads load per open and die with it; writes run on
 * [writes] so they land after the screen is gone, and [confirm] runs on [main] with the outcome.
 */
internal class MusicMenuViewModel(
    private val gateway: CoreGateway,
    private val writes: CoroutineScope,
    /** A favourite write on an item succeeded; the playing session's copy of a song follows. */
    private val favorited: (itemId: String, isFavorite: Boolean) -> Unit,
    private val confirm: (PlaylistOutcome) -> Unit,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val _state = MutableStateFlow(MusicMenuState())
    val state: StateFlow<MusicMenuState> = _state.asStateFlow()

    /** The open menu's reads; a close or a new open cancels them, so a late one never publishes. */
    private var reads: Job? = null
    private var adminRead = false

    fun open(target: MusicMenuTarget, returnKey: String?) {
        reads?.cancel()
        _state.update { MusicMenuState(target = target, returnKey = returnKey, isFavorite = target.card.isFavorite, isAdministrator = it.isAdministrator) }
        if (!adminRead) {
            adminRead = true
            viewModelScope.launch {
                val admin = runCatchingCancellable { gateway.isAdministrator() }.getOrDefault(false)
                _state.update { it.copy(isAdministrator = admin) }
            }
        }
    }

    fun close() {
        reads?.cancel()
        _state.update { MusicMenuState(isAdministrator = it.isAdministrator) }
    }

    /** docs/19 §1.5: Back goes up one level, and closes from the first. */
    fun back() = when (_state.value.level) {
        MusicMenuLevel.First -> close()
        MusicMenuLevel.Playlists -> _state.update { it.copy(level = MusicMenuLevel.First) }
        MusicMenuLevel.AllPlaylists, MusicMenuLevel.NewPlaylist -> _state.update { it.copy(level = MusicMenuLevel.Playlists) }
    }

    /** docs/29 §9 L1: the row target's heart flips in place; a failed write puts it back. */
    fun toggleFavorite() {
        val target = _state.value.target ?: return
        val next = !_state.value.isFavorite
        _state.update { it.copy(isFavorite = next) }
        viewModelScope.launch {
            if (runCatchingCancellable { gateway.setFavorite(target.card.id, next) }.isFailure) {
                _state.update { if (it.target == target) it.copy(isFavorite = !next) else it }
            } else {
                favorited(target.card.id, next)
            }
        }
    }

    /** docs/29 §9 E2: the recent playlists, one past the shown limit, and which already hold a song target. */
    fun enterPlaylists() {
        val target = _state.value.target ?: return
        _state.update { it.copy(level = MusicMenuLevel.Playlists, recent = PlaylistsLoad.Loading) }
        reads?.cancel()
        reads = viewModelScope.launch {
            val page = runCatchingCancellable { gateway.playlists(0u, (RECENT_PLAYLISTS + 1).toUInt()) }.getOrNull()
            if (page == null) {
                _state.update { it.copy(recent = PlaylistsLoad.Failed) }
                return@launch
            }
            val holding = if (target.kind == MusicMenuKind.Song) {
                page.take(RECENT_PLAYLISTS).map { list ->
                    async { list.id.takeIf { runCatchingCancellable { gateway.playlistEntryIds(list.id, target.card.id) }.getOrNull().orEmpty().isNotEmpty() } }
                }.awaitAll().filterNotNull().toSet()
            } else {
                emptySet()
            }
            _state.update { it.copy(recent = PlaylistsLoad.Ready(page, holding)) }
        }
    }

    /** docs/29 §9 E2 "All playlists…": every playlist, read a page at a time, without the ✓ scan. */
    fun enterAllPlaylists() {
        _state.update { it.copy(level = MusicMenuLevel.AllPlaylists, all = PlaylistsLoad.Loading) }
        reads?.cancel()
        reads = viewModelScope.launch {
            val all = runCatchingCancellable { readPages(0, Int.MAX_VALUE) { o, l -> gateway.playlists(o, l) } }.getOrNull()
            _state.update { it.copy(all = all?.let { read -> PlaylistsLoad.Ready(read.rows) } ?: PlaylistsLoad.Failed) }
        }
    }

    fun enterNewPlaylist() = _state.update { it.copy(level = MusicMenuLevel.NewPlaylist) }

    /** docs/29 §9 E2: closes, then adds; a playlist that already held the song still gets it again. */
    fun addTo(playlist: Card, again: Boolean) {
        val target = _state.value.target ?: return
        close()
        write(playlist.name, again) { gateway.addToPlaylist(playlist.id, listOf(target.card.id)) }
    }

    /** docs/29 §9 E3: creates [name] (trimmed, otherwise verbatim) holding the target; absent while blank. */
    fun createAndAdd(name: String) {
        val target = _state.value.target ?: return
        if (!canCreatePlaylist(name)) return
        val trimmed = name.trim()
        close()
        write(trimmed, again = false) { gateway.createPlaylist(trimmed, listOf(target.card.id)) }
    }

    /** docs/29 §9 E: removes the entry, then [onRemoved] places focus; the page re-reads on the gateway's edit (docs/29 §4). */
    fun removeFromPlaylist(action: MusicMenuAction.RemoveFromPlaylist, onRemoved: () -> Unit) {
        close()
        writes.launch {
            val removed = runCatchingCancellable { gateway.removeFromPlaylist(action.playlistId, listOf(action.entryId)) }.isSuccess
            if (removed) withContext(main) { onRemoved() }
        }
    }

    /** docs/19 §1.4: fire and forget, as the video menu does. */
    fun refreshMetadata() {
        val target = _state.value.target ?: return
        close()
        writes.launch { runCatchingCancellable { gateway.refreshMetadata(target.card.id) } }
    }

    private fun write(playlist: String, again: Boolean, call: suspend () -> Unit) {
        writes.launch {
            val ok = runCatchingCancellable { call() }.isSuccess
            withContext(main) { confirm(if (ok) PlaylistOutcome.Added(playlist, again) else PlaylistOutcome.Failed(playlist)) }
        }
    }

    class Factory(
        private val gateway: CoreGateway,
        private val writes: CoroutineScope,
        private val favorited: (String, Boolean) -> Unit,
        private val confirm: (PlaylistOutcome) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicMenuViewModel(gateway, writes, favorited, confirm) as T
    }
}
