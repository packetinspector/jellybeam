package tv.jellybeam.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.common.ChangeRefreshScheduler
import tv.jellybeam.ui.common.serverHostLabel
import uniffi.jellybeam_core.AlbumSummary
import uniffi.jellybeam_core.ArtistScope
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.GridFilters
import uniffi.jellybeam_core.GridGroup
import uniffi.jellybeam_core.GridSort
import uniffi.jellybeam_core.GridSortField
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.LibraryGridPrefs
import uniffi.jellybeam_core.MusicArtistRow
import uniffi.jellybeam_core.SongSource
import uniffi.jellybeam_core.StatusFilter
import uniffi.jellybeam_core.ViewSnapshot
import uniffi.jellybeam_core.WatchedFilter

/** docs/29 §9 A Genres tab: the mirror's verbatim name and counts, with the live list's art when it has some. */
internal data class GenreTile(val name: String, val albumCount: Long, val songCount: Long, val art: ArtSource)

/** One tab's rows; only the selected tab's are held. */
internal sealed interface TabItems {
    val size: Int

    data class Albums(val cards: List<Card>) : TabItems { override val size get() = cards.size }
    data class Artists(val rows: List<MusicArtistRow>) : TabItems { override val size get() = rows.size }
    data class Songs(val cards: List<Card>) : TabItems { override val size get() = cards.size }
    data class Playlists(val cards: List<Card>) : TabItems { override val size get() = cards.size }
    data class Genres(val tiles: List<GenreTile>) : TabItems { override val size get() = tiles.size }
}

/** A tab load: in flight, settled, or failed at [atMs] (docs/29 §10 S3). */
internal sealed interface MusicLoad {
    data object Loading : MusicLoad
    data object Ready : MusicLoad
    data class Failed(val atMs: Long, val host: String?) : MusicLoad
}

/** docs/29 §9 A/E3: the Playlists tab's New playlist panel. */
internal sealed interface NewPlaylistStep {
    data object Closed : NewPlaylistStep

    /** The name panel is open; [failed] after the server refused the last create. */
    data class Naming(val failed: Boolean = false) : NewPlaylistStep

    /** The create is at the server; the panel is inert until it answers. */
    data object Creating : NewPlaylistStep

    /** Shown once: the screen opens [playlist], then calls [MusicLibraryViewModel.createdShown]. */
    data class Created(val playlist: Card) : NewPlaylistStep
}

internal data class MusicLibraryState(
    /** `null` until the persisted tab is read on first activation. */
    val tab: MusicTab? = null,
    val tabs: List<MusicTab> = visibleMusicTabs(hasPlaylists = false, playlistsViewExists = false),
    val artistScope: ArtistScope = ArtistScope.ALBUM_ARTISTS,
    val load: MusicLoad = MusicLoad.Loading,
    val items: TabItems? = null,
    /** The whole list's length (the sort strip); the loaded rows may be fewer. `null` while live playlists may have more pages. */
    val count: Long? = 0L,
    /** A–Z runs for the rail, in list order. */
    val groups: List<GridGroup> = emptyList(),
    val hasMore: Boolean = false,
    /** Album header counts for the focused-item facts, read on focus. */
    val summaries: Map<String, AlbumSummary> = emptyMap(),
    val newPlaylist: NewPlaylistStep = NewPlaylistStep.Closed,
)

/**
 * docs/29 §9 A: one music library's landing. Starts inactive and loads on its first activation
 * (ruling K1). A tab switch cancels the running load and page jobs, so only the selected tab's
 * result publishes. Every list pages [PAGE] at a time.
 */
internal class MusicLibraryViewModel(
    private val gateway: CoreGateway,
    private val view: ViewSnapshot,
    /** Where the last-used tab is written, so leaving the screen mid-write still persists it. */
    private val persistScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(MusicLibraryState())
    val state: StateFlow<MusicLibraryState> = _state.asStateFlow()

    private val active = MutableStateFlow(false)
    private var started = false
    private var prefs: LibraryGridPrefs? = null
    private var loadJob: Job? = null
    private var pageJob: Job? = null

    init {
        ChangeRefreshScheduler(
            scope = viewModelScope,
            events = gateway.changeEvents(),
            active = active,
            refresh = { _state.value.tab?.let { reload(it, keepRows = true).join() } },
        )
    }

    fun setActive(isActive: Boolean) {
        active.value = isActive
        if (isActive && !started) {
            started = true
            loadJob = viewModelScope.launch { start() }
        }
    }

    private suspend fun start() {
        val saved = runCatchingCancellable { gateway.getLibraryGridPrefs(view.id) }.getOrNull()
        prefs = saved
        val hasPlaylists = runCatchingCancellable { gateway.hasPlaylists() }.getOrDefault(false)
        val tabs = visibleMusicTabs(hasPlaylists, playlistsViewExists(gateway.viewsState.value))
        val tab = openingMusicTab(musicTabForItemType(saved?.filters?.itemType), tabs)
        _state.update { it.copy(tab = tab, tabs = tabs) }
        loadTab(tab, keepRows = false)
    }

    /** docs/29 §9 A: tabs switch on OK; the pick is the library's last-used tab from now on. */
    fun selectTab(tab: MusicTab) {
        val current = _state.value.tab ?: return
        if (tab == current || tab !in _state.value.tabs) return
        _state.update { it.copy(tab = tab, load = MusicLoad.Loading, items = null, count = 0L, groups = emptyList(), hasMore = false) }
        reload(tab, keepRows = false)
        val base = prefs ?: LibraryGridPrefs(GridSort(GridSortField.NAME, descending = false), defaultFilters())
        val next = base.copy(filters = base.filters.copy(itemType = tab.itemType))
        prefs = next
        persistScope.launch { runCatchingCancellable { gateway.setLibraryGridPrefs(view.id, next) } }
    }

    fun selectArtistScope(scope: ArtistScope) {
        if (scope == _state.value.artistScope) return
        _state.update { it.copy(artistScope = scope, load = MusicLoad.Loading, items = null, count = 0L, groups = emptyList(), hasMore = false) }
        reload(MusicTab.Artists, keepRows = false)
    }

    /** docs/29 §10 S3: Retry reloads the current tab. */
    fun retry() {
        val tab = _state.value.tab ?: return
        _state.update { it.copy(load = MusicLoad.Loading) }
        reload(tab, keepRows = false)
    }

    private fun reload(tab: MusicTab, keepRows: Boolean): Job {
        loadJob?.cancel()
        pageJob?.cancel()
        return viewModelScope.launch { loadTab(tab, keepRows) }.also { loadJob = it }
    }

    /** First page (or, on a refresh, as many rows as are shown), the count and the rail runs. */
    private suspend fun loadTab(tab: MusicTab, keepRows: Boolean) {
        val shown = if (keepRows) (_state.value.items?.size ?: 0).coerceAtLeast(PAGE) else PAGE
        val scope = _state.value.artistScope
        val result = runCatchingCancellable { fetch(tab, scope, 0, shown) }
        val page = result.getOrNull()
        if (page == null) {
            if (keepRows && _state.value.load == MusicLoad.Ready) return
            val host = runCatchingCancellable { serverHostLabel(gateway.serverInfoSnapshot().serverUrl) }.getOrNull()
            _state.update { it.copy(load = MusicLoad.Failed(clock(), host)) }
            return
        }
        _state.update {
            it.copy(load = MusicLoad.Ready, items = page.items, count = page.count, groups = page.groups, hasMore = page.hasMore)
        }
        if (page.items is TabItems.Genres) fillGenreArt()
    }

    /** docs/29 §7: genre art is live and optional, so it follows the shown tiles instead of holding them back. */
    private suspend fun fillGenreArt() {
        val art = runCatchingCancellable { readPages(0, Int.MAX_VALUE) { o, l -> gateway.musicGenresLive(view.id, o, l) }?.rows }.getOrNull()
            ?.filter { it.primaryTag != null }
            ?.associate { it.name to ArtSource.Own(it.id, it.primaryTag!!, ImageKind.PRIMARY) }
            ?: return
        _state.update { s -> (s.items as? TabItems.Genres)?.let { s.copy(items = TabItems.Genres(withGenreArt(it.tiles, art))) } ?: s }
    }

    /** docs/29 §7: the next [PAGE] rows of the selected tab. */
    fun loadMore() {
        val current = _state.value
        val tab = current.tab ?: return
        if (!current.hasMore || current.load != MusicLoad.Ready || pageJob?.isActive == true) return
        pageJob = viewModelScope.launch {
            val offset = current.items?.size ?: 0
            val next = runCatchingCancellable { fetch(tab, current.artistScope, offset, PAGE, known = current.count) }.getOrNull() ?: return@launch
            _state.update { it.copy(items = append(it.items, next.items), count = next.count, hasMore = next.hasMore && next.items.size > 0) }
        }
    }

    /** The A–Z rail's jump: pages load until row [offset] is held, or the list ends or a page fails. */
    suspend fun loadThrough(offset: Int) {
        while (true) {
            pageJob?.join()
            val before = _state.value.items?.size ?: 0
            if (before > offset || !_state.value.hasMore) return
            loadMore()
            pageJob?.join()
            if ((_state.value.items?.size ?: 0) == before) return
        }
    }

    /** docs/29 §9 A facts: an album's track count and length, read once per album on focus. */
    fun albumFocused(albumId: String) {
        if (albumId in _state.value.summaries) return
        viewModelScope.launch {
            val summary = runCatchingCancellable { gateway.albumSummary(albumId) }.getOrNull() ?: return@launch
            _state.update { it.copy(summaries = it.summaries + (albumId to summary)) }
        }
    }

    fun openNewPlaylist() = _state.update { if (it.newPlaylist == NewPlaylistStep.Closed) it.copy(newPlaylist = NewPlaylistStep.Naming()) else it }

    fun closeNewPlaylist() = _state.update { if (it.newPlaylist is NewPlaylistStep.Naming) it.copy(newPlaylist = NewPlaylistStep.Closed) else it }

    fun createdShown() = _state.update { if (it.newPlaylist is NewPlaylistStep.Created) it.copy(newPlaylist = NewPlaylistStep.Closed) else it }

    /**
     * docs/29 §9 A/E3: an empty playlist named [name] (trimmed, otherwise verbatim). The tab re-reads its first
     * page, newest first, which also yields the new playlist's card to open; a card the page lacks just closes the panel.
     */
    fun createPlaylist(name: String) {
        if (_state.value.newPlaylist !is NewPlaylistStep.Naming || !canCreatePlaylist(name)) return
        _state.update { it.copy(newPlaylist = NewPlaylistStep.Creating) }
        // The write runs on [persistScope], so leaving the screen mid-create still creates the playlist.
        val created = persistScope.async { runCatchingCancellable { gateway.createPlaylist(name.trim(), emptyList()) }.getOrNull() }
        viewModelScope.launch {
            val id = created.await()
            if (id == null) {
                _state.update { it.copy(newPlaylist = NewPlaylistStep.Naming(failed = true)) }
                return@launch
            }
            if (_state.value.tab == MusicTab.Playlists) reload(MusicTab.Playlists, keepRows = true).join()
            val card = (_state.value.items as? TabItems.Playlists)?.cards?.firstOrNull { sameItemId(it.id, id) }
                ?: runCatchingCancellable { gateway.playlists(0u, PAGE.toUInt()) }.getOrNull()?.firstOrNull { sameItemId(it.id, id) }
            _state.update { it.copy(newPlaylist = card?.let(NewPlaylistStep::Created) ?: NewPlaylistStep.Closed) }
        }
    }

    /** [count] `null` while a live list may have more pages; [hasMore] says whether rows past these exist. */
    private class Page(val items: TabItems, val count: Long?, val groups: List<GridGroup>, val hasMore: Boolean)

    /**
     * Up to [want] rows from [offset]; `null` when the source can't answer now (the mirror isn't open, or a live call failed).
     * A next page passes the [known] count, so it reads rows only and leaves the count and rail as they are.
     */
    private suspend fun fetch(tab: MusicTab, scope: ArtistScope, offset: Int, want: Int, known: Long? = null): Page? = when (tab) {
        MusicTab.Albums -> {
            val filters = defaultFilters().copy(itemType = tab.itemType)
            val sort = GridSort(GridSortField.NAME, descending = false)
            val cards = readPages(offset, want) { o, l -> gateway.libraryGrid(view.id, sort, filters, o, l) }?.rows
            val count = known ?: gateway.libraryGridCounts(view.id, filters)?.filtered?.toLong()
            if (cards == null || count == null) null else Page(TabItems.Albums(cards), count, if (known != null) emptyList() else gateway.libraryGridGroups(view.id, sort, filters).orEmpty(), offset + cards.size < count)
        }
        MusicTab.Artists -> {
            val rows = readPages(offset, want) { o, l -> gateway.musicArtists(view.id, scope, o, l) }?.rows
            val count = known ?: gateway.musicArtistCount(view.id, scope)?.toLong()
            if (rows == null || count == null) null else Page(TabItems.Artists(rows), count, if (known != null) emptyList() else gateway.musicArtistGroups(view.id, scope).orEmpty(), offset + rows.size < count)
        }
        MusicTab.Songs -> {
            val source = SongSource.All(view.id)
            val cards = readPages(offset, want) { o, l -> gateway.musicSongs(source, o, l) }?.rows
            val count = known ?: gateway.musicSongCount(source)?.toLong()
            if (cards == null || count == null) null else Page(TabItems.Songs(cards), count, if (known != null) emptyList() else gateway.musicSongGroups(source).orEmpty(), offset + cards.size < count)
        }
        MusicTab.Playlists -> readPages(offset, want) { o, l -> gateway.playlists(o, l) }?.let { read ->
            // Live, with no total: a full last page means there may be more, and the count is known once the end is.
            Page(TabItems.Playlists(read.rows), (offset + read.rows.size).toLong().takeUnless { read.more }, emptyList(), read.more)
        }
        MusicTab.Genres -> gateway.musicGenres(view.id)?.let { rows ->
            // The mirror's tiles publish at once, keeping art already shown; [fillGenreArt] adds the rest.
            val held = (_state.value.items as? TabItems.Genres)?.tiles.orEmpty().associate { it.name to it.art }
            val tiles = withGenreArt(rows.map { GenreTile(it.name, it.albumCount.toLong(), it.songCount.toLong(), ArtSource.None) }, held)
            Page(TabItems.Genres(tiles), tiles.size.toLong(), emptyList(), hasMore = false)
        }
    }

    private fun append(held: TabItems?, more: TabItems): TabItems = when {
        held is TabItems.Albums && more is TabItems.Albums -> TabItems.Albums(held.cards + more.cards)
        held is TabItems.Artists && more is TabItems.Artists -> TabItems.Artists(held.rows + more.rows)
        held is TabItems.Songs && more is TabItems.Songs -> TabItems.Songs(held.cards + more.cards)
        held is TabItems.Playlists && more is TabItems.Playlists -> TabItems.Playlists(held.cards + more.cards)
        else -> more
    }

    class Factory(private val gateway: CoreGateway, private val view: ViewSnapshot, private val persistScope: CoroutineScope) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicLibraryViewModel(gateway, view, persistScope) as T
    }

    internal companion object {
        /** docs/29 §7: every list pages 100 at a time. */
        const val PAGE = 100
    }
}

internal fun defaultFilters() = GridFilters(watched = WatchedFilter.ANY, genre = null, decade = null, status = StatusFilter.ANY, itemType = null)

/** Each tile takes [art] under its verbatim name when it has none yet. */
internal fun withGenreArt(tiles: List<GenreTile>, art: Map<String, ArtSource>): List<GenreTile> =
    tiles.map { tile -> if (tile.art == ArtSource.None) art[tile.name]?.let { tile.copy(art = it) } ?: tile else tile }
