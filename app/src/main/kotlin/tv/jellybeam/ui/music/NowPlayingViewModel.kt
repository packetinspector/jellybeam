package tv.jellybeam.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.data.runCatchingCancellable
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicPlaybackOwner
import tv.jellybeam.player.music.MusicSession
import tv.jellybeam.player.music.MusicSource
import uniffi.jellybeam_core.AudioFacts
import uniffi.jellybeam_core.AudioStream
import uniffi.jellybeam_core.AudioTranscode
import uniffi.jellybeam_core.Lyrics
import uniffi.jellybeam_core.SyncedLyricLine

/** docs/29 §11: the Now Playing spec line; a transcode is the caution line, shown only because the user opted in. */
internal sealed interface SpecLine {
    data class Direct(val text: String) : SpecLine
    data class Transcode(val text: String) : SpecLine
}

/** docs/29 §9 F2: [Absent] hides the Lyrics pill (no HasLyrics, a 404, or a failed read). */
internal sealed interface LyricsState {
    data object Absent : LyricsState
    data object Loading : LyricsState
    data class Synced(val lines: List<SyncedLyricLine>) : LyricsState
    data class Unsynced(val lines: List<String>) : LyricsState
}

/** What Now Playing reads beyond the player: per track [itemId], and the session's [source] name. */
internal data class NowPlayingExtras(
    val itemId: String? = null,
    val spec: SpecLine? = null,
    val lyrics: LyricsState = LyricsState.Absent,
    /** The heart after a flip on this screen; `null` reads the song's own flag. */
    val favorite: Boolean? = null,
    val sid: Long? = null,
    val source: SourceName = SourceName.Unknown,
)

/** The playing track a load is for; a change of any field is a new load. */
internal data class NowPlayingTrack(val sid: Long, val epoch: ULong, val source: MusicSource, val itemId: String)

internal fun nowPlayingTrack(flow: MusicFlow, view: MusicQueueView?): NowPlayingTrack? {
    val session = flow.session ?: return null
    val now = view?.now ?: return null
    return NowPlayingTrack(session.sid, session.epoch, session.source, now.queue[now.current].item)
}

/** [source]'s name on account [epoch]: an item's verbatim name, a fixed list's title, or [SourceName.Unknown] when unread. */
internal suspend fun resolveSourceName(gateway: CoreGateway, source: MusicSource, epoch: ULong): SourceName = when (val lookup = sourceLookup(source)) {
    is SourceLookup.ById -> sourceName(source, runCatchingCancellable { itemName(gateway, lookup.id, epoch) }.getOrNull())
    is SourceLookup.Verbatim -> sourceName(source, lookup.name)
    is SourceLookup.Fixed -> SourceName.Fixed(lookup.title)
    SourceLookup.None -> sourceName(source, null)
}

/** docs/18 §2.1: the mirror holds only the current account, so a parked account's item is read live on that account. */
private suspend fun itemName(gateway: CoreGateway, itemId: String, epoch: ULong): String? =
    if (epoch == gateway.accountEpochs.value.epoch) gateway.cardById(itemId)?.name else gateway.getItemDetail(itemId, epoch).name

/** docs/29 §11: Direct Play reads the stream facts, a transcode its target; a song that won't play has no line. */
internal fun specLineFor(stream: AudioStream, direct: () -> String?, transcode: (AudioTranscode) -> String): SpecLine? = when (stream) {
    is AudioStream.DirectPlay -> direct()?.ifEmpty { null }?.let(SpecLine::Direct)
    is AudioStream.Transcode -> SpecLine.Transcode(transcode(stream.target))
    is AudioStream.WouldTranscode, is AudioStream.Unplayable -> null
}

/** docs/29 §9 F2: an empty list reads as no lyrics, so the pill hides as on a 404. */
internal fun lyricsStateOf(lyrics: Lyrics): LyricsState = when (lyrics) {
    is Lyrics.Synced -> if (lyrics.lines.isEmpty()) LyricsState.Absent else LyricsState.Synced(lyrics.lines)
    is Lyrics.Unsynced -> if (lyrics.lines.isEmpty()) LyricsState.Absent else LyricsState.Unsynced(lyrics.lines)
    Lyrics.None -> LyricsState.Absent
}

/**
 * docs/29 §9 F: the playing track's spec line, lyrics (only when it has them), artist page and the
 * queue's source name. Follows the owner only while active (ruling K1); a track change cancels the
 * previous track's reads, so an older track's result never publishes.
 */
internal class NowPlayingViewModel(
    private val gateway: CoreGateway,
    private val owner: MusicPlaybackOwner,
    private val directLine: (AudioFacts) -> String,
    private val transcodeLine: (AudioTranscode) -> String,
) : ViewModel() {
    private val _state = MutableStateFlow(NowPlayingExtras())
    val state: StateFlow<NowPlayingExtras> = _state.asStateFlow()
    private var following: Job? = null

    fun setActive(isActive: Boolean) {
        following?.cancel()
        following = if (isActive) viewModelScope.launch { follow() } else null
    }

    /** docs/29 §9 L: the heart flips in place; a failed write puts it back. */
    fun toggleFavorite(itemId: String, isFavorite: Boolean) {
        if (_state.value.itemId != itemId) return
        _state.update { it.copy(favorite = !isFavorite) }
        viewModelScope.launch {
            if (runCatchingCancellable { gateway.setFavorite(itemId, !isFavorite) }.isFailure) {
                _state.update { if (it.itemId == itemId) it.copy(favorite = isFavorite) else it }
            } else {
                owner.favoriteChanged(itemId, !isFavorite)
            }
        }
    }

    /** The track whose reads all finished; an interrupted load runs again on the next activation. */
    private var loaded: NowPlayingTrack? = null

    private suspend fun follow() {
        combine(owner.state, owner.now) { flow, now -> nowPlayingTrack(flow, musicQueueView(flow, now)) }
            .distinctUntilChanged()
            .collectLatest { track ->
                if (track == null || track == loaded) return@collectLatest
                load(track)
                loaded = track
            }
    }

    private suspend fun load(track: NowPlayingTrack) {
        val card = owner.card(track.itemId)
        _state.update {
            NowPlayingExtras(
                itemId = track.itemId,
                lyrics = if (card?.hasLyrics == true) LyricsState.Loading else LyricsState.Absent,
                sid = track.sid,
                source = if (it.sid == track.sid) it.source else SourceName.Unknown,
            )
        }
        if (card?.hasLyrics == true) {
            val lyrics = runCatchingCancellable { gateway.lyrics(track.itemId, track.epoch) }.getOrNull()
            _state.update { it.copy(lyrics = lyrics?.let(::lyricsStateOf) ?: LyricsState.Absent) }
        }
        if (_state.value.source == SourceName.Unknown) {
            val name = resolveSourceName(gateway, track.source, track.epoch)
            _state.update { it.copy(source = name) }
        }
        val stream = runCatchingCancellable { owner.plan(track.epoch, track.itemId) }.getOrNull()?.stream
        val spec = stream?.let { s ->
            val facts = if (s is AudioStream.DirectPlay) runCatchingCancellable { gateway.audioFacts(track.itemId, track.epoch) }.getOrNull() else null
            specLineFor(s, direct = { facts?.let(directLine) }, transcode = transcodeLine)
        }
        _state.update { it.copy(spec = spec) }
    }

    class Factory(
        private val gateway: CoreGateway,
        private val owner: MusicPlaybackOwner,
        private val directLine: (AudioFacts) -> String,
        private val transcodeLine: (AudioTranscode) -> String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NowPlayingViewModel(gateway, owner, directLine, transcodeLine) as T
    }
}

/** docs/29 §9 H: the Queue screen reads only its source's name; resolved once per session while active (ruling K1). */
internal class MusicQueueViewModel(private val gateway: CoreGateway, private val owner: MusicPlaybackOwner) : ViewModel() {
    private val _state = MutableStateFlow<Pair<Long, SourceName>?>(null)

    /** The session id and the name of what it plays from. */
    val state: StateFlow<Pair<Long, SourceName>?> = _state.asStateFlow()
    private var following: Job? = null

    fun setActive(isActive: Boolean) {
        following?.cancel()
        following = if (isActive) viewModelScope.launch { follow() } else null
    }

    private suspend fun follow() {
        owner.state
            .map { flow -> flow.session }
            .distinctUntilChanged { a, b -> a?.sid == b?.sid }
            .collectLatest { session ->
                if (session == null || _state.value?.first == session.sid) return@collectLatest
                _state.value = session.sid to resolveSourceName(gateway, session.source, session.epoch)
            }
    }

    class Factory(private val gateway: CoreGateway, private val owner: MusicPlaybackOwner) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MusicQueueViewModel(gateway, owner) as T
    }
}

/** The session the flow plays or starts, `null` while idle. */
internal val MusicFlow.session: MusicSession?
    get() = when (val p = phase) {
        MusicFlow.Phase.Idle -> null
        is MusicFlow.Phase.Starting -> p.session
        is MusicFlow.Phase.Active -> p.session
    }
