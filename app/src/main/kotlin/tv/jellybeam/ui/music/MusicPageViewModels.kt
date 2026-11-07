package tv.jellybeam.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import tv.jellybeam.ui.common.serverHostLabel
import uniffi.jellybeam_core.AlbumSummary
import uniffi.jellybeam_core.AudioFacts
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.GridGroup
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.MusicArtistRow
import uniffi.jellybeam_core.PlaylistEntry
import uniffi.jellybeam_core.SongSource

/** The shared shape of a music page's load (docs/29 §10 S1–S3); [MusicLoad] is the landing's. */
private suspend fun CoreGateway.failedNow(clock: () -> Long): MusicLoad.Failed =
    MusicLoad.Failed(clock(), runCatchingCancellable { serverHostLabel(serverInfoSnapshot().serverUrl) }.getOrNull())

internal data class MusicAlbumState(
    val load: MusicLoad = MusicLoad.Loading,
    val album: Card? = null,
    val summary: AlbumSummary? = null,
    val tracks: List<Card> = emptyList(),
    val hasMore: Boolean = false,
    /** docs/29 §11 capsule from the first track; `null` until read or when nothing is known. */
    val specLine: String? = null,
    /** Verbatim genres, `" · "`-joined into the one chip. */
    val genre: String? = null,
    val isFavorite: Boolean = false,
)

/**
 * docs/29 §9 C: one album. Loads on first activation (ruling K1); the header from the mirror, the
 * tracks a page of [MusicLibraryViewModel.PAGE] at a time, the spec capsule live from the first track.
 */
internal class MusicAlbumViewModel(
    private val gateway: CoreGateway,
    private val albumId: String,
    private val specLine: (AudioFacts) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(MusicAlbumState())
    val state: StateFlow<MusicAlbumState> = _state.asStateFlow()
    private val active = MutableStateFlow(false)
    private var started = false
    private var loadJob: Job? = null
    private var pageJob: Job? = null

    init {
        ChangeRefreshScheduler(viewModelScope, gateway.changeEvents(), active, refresh = { reload().join() })
    }

    fun setActive(isActive: Boolean) {
        active.value = isActive
        if (isActive && !started) {
            started = true
            reload()
        }
    }

    fun retry() {
        _state.update { it.copy(load = MusicLoad.Loading) }
        reload()
    }

    private fun reload(): Job {
        loadJob?.cancel()
        pageJob?.cancel()
        return viewModelScope.launch { load() }.also { loadJob = it }
    }

    private suspend fun load() {
        val shown = _state.value.tracks.size.coerceAtLeast(MusicLibraryViewModel.PAGE)
        val album = runCatchingCancellable { gateway.cardById(albumId) }.getOrNull()
        val tracks = readPages(0, shown) { o, l -> gateway.albumTracks(albumId, o, l) }?.rows
        if (album == null || tracks == null) {
            if (_state.value.load != MusicLoad.Ready) _state.update { it.copy(load = gateway.failedNow(clock)) }
            return
        }
        val summary = gateway.albumSummary(albumId)
        _state.update {
            it.copy(
                load = MusicLoad.Ready,
                album = album,
                summary = summary,
                tracks = tracks,
                hasMore = tracks.size < (summary?.trackCount?.toInt() ?: tracks.size),
                isFavorite = album.isFavorite,
            )
        }
        val genre = runCatchingCancellable { gateway.itemDetailLocal(albumId) }.getOrNull()?.genres?.joinToString(" · ")?.ifEmpty { null }
        _state.update { it.copy(genre = genre) }
        val spec = tracks.firstOrNull()?.let { first -> runCatchingCancellable { specLine(gateway.audioFacts(first.id)) }.getOrNull() }
        _state.update { it.copy(specLine = spec?.ifEmpty { null }) }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || pageJob?.isActive == true) return
        val offset = current.tracks.size
        pageJob = viewModelScope.launch {
            val more = gateway.albumTracks(albumId, offset.toUInt(), MusicLibraryViewModel.PAGE.toUInt()) ?: return@launch
            _state.update { it.copy(tracks = it.tracks + more, hasMore = more.isNotEmpty() && offset + more.size < (it.summary?.trackCount?.toInt() ?: 0)) }
        }
    }

    /** docs/29 §9 L: the heart flips in place; a failed write puts it back. */
    fun toggleFavorite() {
        val next = !_state.value.isFavorite
        _state.update { it.copy(isFavorite = next) }
        viewModelScope.launch {
            if (runCatchingCancellable { gateway.setFavorite(albumId, next) }.isFailure) _state.update { it.copy(isFavorite = !next) }
        }
    }

    class Factory(private val gateway: CoreGateway, private val albumId: String, private val specLine: (AudioFacts) -> String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicAlbumViewModel(gateway, albumId, specLine) as T
    }
}

internal const val MUSIC_ARTIST_TYPE = "MusicArtist"

internal data class MusicArtistState(
    val load: MusicLoad = MusicLoad.Loading,
    val artist: MusicArtistRow? = null,
    /** The artist's mirrored card: its favorite flag and backdrop. */
    val card: Card? = null,
    val mostPlayed: List<Card> = emptyList(),
    val albums: List<Card> = emptyList(),
    val appearsOn: List<Card> = emptyList(),
    /** docs/29 §9 B2: a per-track performer's songs, shown only when it has no albums, a page at a time. */
    val songs: List<Card> = emptyList(),
    val songsMore: Boolean = false,
    val isFavorite: Boolean = false,
)

/** docs/29 §9 B: one artist; header, Most played and the two shelves from the mirror. */
internal class MusicArtistViewModel(
    private val gateway: CoreGateway,
    private val artistId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(MusicArtistState())
    val state: StateFlow<MusicArtistState> = _state.asStateFlow()
    private val active = MutableStateFlow(false)
    private var started = false
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private val songSource = SongSource.Artist(artistId, ARTIST_SONG_SORT)

    init {
        ChangeRefreshScheduler(viewModelScope, gateway.changeEvents(), active, refresh = { reload().join() })
    }

    fun setActive(isActive: Boolean) {
        active.value = isActive
        if (isActive && !started) {
            started = true
            reload()
        }
    }

    fun retry() {
        _state.update { it.copy(load = MusicLoad.Loading) }
        reload()
    }

    private fun reload(): Job {
        loadJob?.cancel()
        pageJob?.cancel()
        return viewModelScope.launch { load() }.also { loadJob = it }
    }

    private suspend fun load() {
        val artist = runCatchingCancellable { gateway.artistSummary(artistId) }.getOrNull()
        if (artist == null) {
            if (_state.value.load != MusicLoad.Ready) _state.update { it.copy(load = gateway.failedNow(clock)) }
            return
        }
        val card = runCatchingCancellable { gateway.cardById(artistId) }.getOrNull()
        val albums = gateway.artistAlbums(artistId).orEmpty()
        val shown = _state.value.songs.size.coerceAtLeast(MusicLibraryViewModel.PAGE)
        val songs = if (albums.isEmpty()) readPages(0, shown) { o, l -> gateway.musicSongs(songSource, o, l) } else null
        _state.update {
            it.copy(
                load = MusicLoad.Ready,
                artist = artist,
                card = card,
                mostPlayed = gateway.artistMostPlayed(artistId, MOST_PLAYED).orEmpty(),
                albums = albums,
                appearsOn = gateway.artistAppearsOn(artistId).orEmpty(),
                songs = songs?.rows.orEmpty(),
                songsMore = songs?.more == true,
                isFavorite = card?.isFavorite == true,
            )
        }
    }

    /** docs/29 §9 B2: the performer's next page of songs. */
    fun loadMoreSongs() {
        val current = _state.value
        if (!current.songsMore || pageJob?.isActive == true) return
        val offset = current.songs.size
        pageJob = viewModelScope.launch {
            val more = gateway.musicSongs(songSource, offset.toUInt(), MusicLibraryViewModel.PAGE.toUInt()) ?: return@launch
            _state.update { it.copy(songs = it.songs + more, songsMore = more.size == MusicLibraryViewModel.PAGE) }
        }
    }

    fun toggleFavorite() {
        val next = !_state.value.isFavorite
        _state.update { it.copy(isFavorite = next) }
        viewModelScope.launch {
            if (runCatchingCancellable { gateway.setFavorite(artistId, next) }.isFailure) _state.update { it.copy(isFavorite = !next) }
        }
    }

    class Factory(private val gateway: CoreGateway, private val artistId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicArtistViewModel(gateway, artistId) as T
    }

    private companion object {
        /** docs/29 §9 B: the top five. */
        const val MOST_PLAYED = 5u
        val ARTIST_SONG_SORT = GridSort(GridSortField.YEAR, descending = false)
    }
}

internal data class MusicSongsState(
    val load: MusicLoad = MusicLoad.Loading,
    val songs: List<Card> = emptyList(),
    val count: Long = 0L,
    val groups: List<GridGroup> = emptyList(),
    val hasMore: Boolean = false,
)

/** docs/29 §9 D: one song list, whatever its source, paged [MusicLibraryViewModel.PAGE] at a time. */
internal class MusicSongsViewModel(
    private val gateway: CoreGateway,
    private val source: SongSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(MusicSongsState())
    val state: StateFlow<MusicSongsState> = _state.asStateFlow()
    private val active = MutableStateFlow(false)
    private var started = false
    private var loadJob: Job? = null
    private var pageJob: Job? = null

    init {
        ChangeRefreshScheduler(viewModelScope, gateway.changeEvents(), active, refresh = { reload().join() })
    }

    fun setActive(isActive: Boolean) {
        active.value = isActive
        if (isActive && !started) {
            started = true
            reload()
        }
    }

    fun retry() {
        _state.update { it.copy(load = MusicLoad.Loading) }
        reload()
    }

    private fun reload(): Job {
        loadJob?.cancel()
        pageJob?.cancel()
        return viewModelScope.launch { load() }.also { loadJob = it }
    }

    private suspend fun load() {
        val shown = _state.value.songs.size.coerceAtLeast(MusicLibraryViewModel.PAGE)
        val songs = readPages(0, shown) { o, l -> gateway.musicSongs(source, o, l) }?.rows
        val count = gateway.musicSongCount(source)
        if (songs == null || count == null) {
            if (_state.value.load != MusicLoad.Ready) _state.update { it.copy(load = gateway.failedNow(clock)) }
            return
        }
        val groups = if (songColumns(source).rail) gateway.musicSongGroups(source).orEmpty() else emptyList()
        _state.update { it.copy(load = MusicLoad.Ready, songs = songs, count = count.toLong(), groups = groups, hasMore = songs.size < count.toLong()) }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || pageJob?.isActive == true) return
        pageJob = viewModelScope.launch {
            val more = gateway.musicSongs(source, current.songs.size.toUInt(), MusicLibraryViewModel.PAGE.toUInt()) ?: return@launch
            _state.update { it.copy(songs = it.songs + more, hasMore = more.isNotEmpty() && it.songs.size + more.size < it.count) }
        }
    }

    class Factory(private val gateway: CoreGateway, private val source: SongSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicSongsViewModel(gateway, source) as T
    }
}

internal data class MusicPlaylistState(
    val load: MusicLoad = MusicLoad.Loading,
    /** Rows in playlist order and the E4 reorder mode; an entry's id is what Remove from playlist and Move name. */
    val reorder: PlaylistReorder = PlaylistReorder(),
    val hasMore: Boolean = false,
    val isFavorite: Boolean = false,
)

/** docs/29 §9 E1/E4: one playlist's rows in playlist order, live, a page at a time, and its reorder mode. */
internal class MusicPlaylistViewModel(
    private val gateway: CoreGateway,
    private val playlist: Card,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(MusicPlaylistState(isFavorite = playlist.isFavorite))
    val state: StateFlow<MusicPlaylistState> = _state.asStateFlow()
    private val active = MutableStateFlow(false)
    private var started = false
    private var loadJob: Job? = null
    private var pageJob: Job? = null

    init {
        // docs/29 §4: live rows carry no mirror events; an add or remove here or elsewhere re-reads once while shown.
        ChangeRefreshScheduler(viewModelScope, gateway.playlistEdits(), active, refresh = { reload().join() }, affects = { it == playlist.id })
    }

    fun setActive(isActive: Boolean) {
        active.value = isActive
        if (isActive && !started) {
            started = true
            reload()
        }
    }

    fun retry() {
        _state.update { it.copy(load = MusicLoad.Loading) }
        reload()
    }

    private fun reload(): Job {
        loadJob?.cancel()
        pageJob?.cancel()
        return viewModelScope.launch {
            val shown = _state.value.reorder.entries.size.coerceAtLeast(MusicLibraryViewModel.PAGE)
            val read = runCatchingCancellable { readPages(0, shown) { o, l -> gateway.playlistItems(playlist.id, o, l) } }.getOrNull()
            if (read == null) {
                // A failed re-read keeps the rows on screen, as the other music pages do.
                if (_state.value.load != MusicLoad.Ready) _state.update { it.copy(load = gateway.failedNow(clock)) }
                return@launch
            }
            _state.update { it.copy(load = MusicLoad.Ready, hasMore = read.more) }
            send(ReorderEvent.Loaded(read.rows))
        }.also { loadJob = it }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || pageJob?.isActive == true) return
        pageJob = viewModelScope.launch {
            val more = runCatchingCancellable {
                gateway.playlistItems(playlist.id, current.reorder.entries.size.toUInt(), MusicLibraryViewModel.PAGE.toUInt())
            }.getOrNull() ?: return@launch
            _state.update { it.copy(hasMore = more.size == MusicLibraryViewModel.PAGE) }
            send(ReorderEvent.Appended(more))
        }
    }

    /** docs/29 §9 E4: ··· → Reorder songs, OK, Up/Down, OK and Back all enter here. */
    fun reorder(event: ReorderEvent) = send(event)

    /** The one writer of [PlaylistReorder]; a drop's move runs here and comes back tagged. */
    private fun send(event: ReorderEvent) {
        var move: ReorderMove? = null
        _state.update { s -> reduceReorder(s.reorder, event).let { step -> move = step.move; s.copy(reorder = step.state) } }
        move?.let { m ->
            viewModelScope.launch {
                val ok = runCatchingCancellable { gateway.movePlaylistItem(playlist.id, m.entryId, m.newIndex.toUInt()) }.isSuccess
                send(if (ok) ReorderEvent.Moved(m.tag) else ReorderEvent.MoveFailed(m.tag))
            }
        }
    }

    fun toggleFavorite() {
        val next = !_state.value.isFavorite
        _state.update { it.copy(isFavorite = next) }
        viewModelScope.launch {
            if (runCatchingCancellable { gateway.setFavorite(playlist.id, next) }.isFailure) _state.update { it.copy(isFavorite = !next) }
        }
    }

    class Factory(private val gateway: CoreGateway, private val playlist: Card) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicPlaylistViewModel(gateway, playlist) as T
    }
}
