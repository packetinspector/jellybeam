package tv.jellybeam.ui.music

import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicNow
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.QueueEntry
import tv.jellybeam.player.music.QueueOrigin
import tv.jellybeam.player.music.QueueReorder
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.player.music.movedIndex
import tv.jellybeam.player.music.queueGroups
import tv.jellybeam.ui.cards.ArtSource
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SongSource
import uniffi.jellybeam_core.SyncedLyricLine

/** The queue as Now Playing and the Queue screen read it: the player's snapshot, or the session's start row before it publishes. */
internal data class MusicQueueView(val now: MusicNow, val source: MusicSource, val loading: Boolean)

/** `null` while nothing holds playback (docs/29 §8.4: these screens exist only with a session). */
internal fun musicQueueView(flow: MusicFlow?, now: MusicNow?): MusicQueueView? {
    val (session, active) = when (val phase = flow?.phase ?: return null) {
        MusicFlow.Phase.Idle -> return null
        is MusicFlow.Phase.Starting -> phase.session to false
        is MusicFlow.Phase.Active -> phase.session to true
    }
    val snapshot = now?.takeIf { active }
    val view = snapshot ?: MusicNow(
        queue = session.items,
        playOrder = session.items.indices.toList(),
        current = session.start,
        isPlaying = false,
        positionMs = 0L,
        durationMs = 0L,
        shuffle = session.shuffled,
        repeat = RepeatMode.Off,
    )
    return MusicQueueView(view, session.source, loading = snapshot == null)
}

/** docs/29 §9 F1/F3: every text line and flag on Now Playing; names verbatim, mono labels cased up. */
internal data class NowPlayingModel(
    val itemId: String,
    val title: String,
    val artistLine: String,
    /** The artist the link opens, by name; `null` hides the chevron. */
    val artistName: String?,
    val albumId: String?,
    val art: ArtSource,
    val meta: String,
    val queueLine: String,
    /** Durations in play order (the shuffled one when shuffled), for [QueueStrip]. */
    val stripDurationsMs: List<Long?>,
    val stripCurrent: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    /** The user paused (F3): PAUSED shows and ENDS AT is dropped. */
    val paused: Boolean,
    val loading: Boolean,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val upNext: UpNext?,
    val hasLyrics: Boolean,
    val isFavorite: Boolean,
)

internal data class UpNext(val title: String, val duration: String?)

internal fun nowPlayingModel(view: MusicQueueView, card: (String) -> Card?, strings: UiStrings): NowPlayingModel {
    val now = view.now
    val item = now.queue[now.current].item
    val song = card(item)
    val position = now.playOrder.indexOf(now.current).coerceAtLeast(0)
    val durations = now.playOrder.map { durationMs(card(now.queue[it].item)) }
    return NowPlayingModel(
        itemId = item,
        title = song?.name.orEmpty(),
        artistLine = song?.let(::musicArtistLine).orEmpty(),
        artistName = song?.let { it.albumArtist?.ifEmpty { null } ?: it.artists.firstOrNull() },
        albumId = song?.albumId,
        art = song?.let(::trackArtSource) ?: ArtSource.None,
        meta = song?.let { trackMetaLine(it, strings) }.orEmpty(),
        queueLine = queueLine(position, now.queue.size, durations, now.shuffle, strings),
        stripDurationsMs = durations,
        stripCurrent = position,
        positionMs = now.positionMs,
        durationMs = trackDurationMs(song, playerMs = now.durationMs),
        isPlaying = now.isPlaying,
        paused = now.paused,
        loading = view.loading,
        shuffle = now.shuffle,
        repeat = now.repeat,
        upNext = upNextEntry(now)?.let { next -> card(now.queue[next].item) }?.let { UpNext(it.name, durationMs(it)?.let(::formatTrackTime)) },
        hasLyrics = song?.hasLyrics == true,
        isFavorite = song?.isFavorite == true,
    )
}

/** docs/29 §9 F: `{album} · {year} · Disc d, track n`, each missing part omitted. */
internal fun trackMetaLine(card: Card, strings: UiStrings): String {
    val track = card.indexNumber
    val disc = card.parentIndexNumber
    val position = when {
        track != null && disc != null -> strings.get(R.string.music_disc_track, disc, track)
        track != null -> strings.get(R.string.music_track_n, track)
        else -> null
    }
    return listOfNotNull(card.album?.ifEmpty { null }, card.productionYear?.toString(), position).joinToString(" · ")
}

/** docs/29 §9 F: `QUEUE │ n OF m │ total MIN`, or `│ SHUFFLED` in its place when shuffled (F3). */
internal fun queueLine(position: Int, size: Int, durationsMs: List<Long?>, shuffled: Boolean, strings: UiStrings): String {
    val tail = if (shuffled) strings.get(R.string.music_shuffled) else musicLength(durationsMs.sumOf { it ?: 0L } * TICKS_PER_MS, strings)
    return listOfNotNull(strings.get(R.string.music_queue_position, position + 1, size), tail).joinToString(" │ ").uppercaseUi()
}

/** The timeline entry that plays after the current one, as the player will pick it; `null` when the queue ends with it. */
internal fun upNextEntry(now: MusicNow): Int? {
    if (now.repeat == RepeatMode.One) return now.current
    val at = now.playOrder.indexOf(now.current)
    return now.playOrder.getOrNull(at + 1) ?: now.playOrder.firstOrNull()?.takeIf { now.repeat == RepeatMode.All }
}

/** docs/29 §9 F/F3: elapsed, `−remaining`, and when the track ends; [endsAtMs] is `null` while paused, since it would drift. */
internal data class TimeRow(val elapsed: String, val remaining: String, val endsAtMs: Long?, val paused: Boolean)

internal fun nowPlayingTimeRow(positionMs: Long, durationMs: Long, paused: Boolean, nowMs: Long): TimeRow = TimeRow(
    elapsed = formatTrackTime(positionMs),
    remaining = "−" + formatTrackTime((durationMs - positionMs).coerceAtLeast(0L)),
    endsAtMs = if (paused || durationMs <= 0L) null else trackEndsAtMs(nowMs, positionMs, durationMs),
    paused = paused,
)

/** docs/29 §9 F strip focus: Left/Right seek by the skip settings, clamped to the track. */
internal fun stripSeekTarget(positionMs: Long, durationMs: Long, forward: Boolean, skipBackMs: Long, skipForwardMs: Long): Long {
    val target = if (forward) positionMs + skipForwardMs else positionMs - skipBackMs
    return if (durationMs > 0L) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
}

/** The synced line sung now ([line], -1 before the first) and the wait until a later one starts (null after the last). */
internal data class LyricCue(val line: Int, val untilNextMs: Long?)

/** docs/29 §9 F2: the cue at [positionMs]; the screen sleeps [LyricCue.untilNextMs], so lines change on time, not on a 1 s tick. */
internal fun lyricCue(lines: List<SyncedLyricLine>, positionMs: Long): LyricCue {
    val ms = positionMs.coerceAtLeast(0L).toULong()
    val line = lines.indexOfLast { it.startMs <= ms }
    val next = lines.firstOrNull { it.startMs > ms }
    return LyricCue(line, next?.let { (it.startMs - ms).toLong() })
}

/** What a queue plays from, as Now Playing and the Queue screen name it. */
internal sealed interface SourceName {
    /** An album, artist, playlist or list, verbatim. */
    data class Named(val kind: SourceKind, val name: String) : SourceName

    /** An Instant Mix, with its seed's name when known. */
    data class Mix(val seed: String?) : SourceName

    /** A fixed song list, titled by the app string [title]. */
    data class Fixed(@androidx.annotation.StringRes val title: Int) : SourceName

    data object Unknown : SourceName
}

internal enum class SourceKind { Album, Artist, Playlist, List }

/** The kind word the Queue screen's NEXT IN QUEUE header ends on. */
internal fun sourceKindOf(source: MusicSource): SourceKind = when (source) {
    is MusicSource.Album, is MusicSource.Single -> SourceKind.Album
    is MusicSource.Artist -> SourceKind.Artist
    is MusicSource.Playlist -> SourceKind.Playlist
    is MusicSource.Songs, is MusicSource.Mix -> SourceKind.List
}

/** The id whose mirror name names [source], or a fixed list's own title; [Single] reads its song's album. */
internal sealed interface SourceLookup {
    data class ById(val id: String) : SourceLookup
    data class Fixed(@androidx.annotation.StringRes val title: Int) : SourceLookup
    data class Verbatim(val name: String) : SourceLookup
    data object None : SourceLookup
}

internal fun sourceLookup(source: MusicSource): SourceLookup = when (source) {
    is MusicSource.Album -> SourceLookup.ById(source.id)
    is MusicSource.Artist -> SourceLookup.ById(source.id)
    is MusicSource.Playlist -> SourceLookup.ById(source.id)
    is MusicSource.Single -> source.song.album?.ifEmpty { null }?.let { SourceLookup.Verbatim(it) } ?: SourceLookup.None
    is MusicSource.Mix -> when (val seed = source.seed) {
        is uniffi.jellybeam_core.MixSeedFfi.Song -> SourceLookup.ById(seed.id)
        is uniffi.jellybeam_core.MixSeedFfi.Album -> SourceLookup.ById(seed.id)
        is uniffi.jellybeam_core.MixSeedFfi.Artist -> SourceLookup.ById(seed.id)
        is uniffi.jellybeam_core.MixSeedFfi.Playlist -> SourceLookup.ById(seed.id)
        is uniffi.jellybeam_core.MixSeedFfi.Genre -> SourceLookup.Verbatim(seed.name)
    }
    is MusicSource.Songs -> when (val list = source.source) {
        is SongSource.All -> SourceLookup.Fixed(R.string.music_tab_songs)
        is SongSource.Favorites -> SourceLookup.Fixed(R.string.music_songs_favorites)
        is SongSource.RecentlyPlayed -> SourceLookup.Fixed(R.string.music_songs_recent)
        is SongSource.Genre -> SourceLookup.Verbatim(list.genre)
        is SongSource.Artist -> SourceLookup.ById(list.artistId)
    }
}

/** [source] named from its looked-up [name]; `null` [name] is unknown, unless the source is a mix, which names itself. */
internal fun sourceName(source: MusicSource, name: String?): SourceName = when {
    source is MusicSource.Mix -> SourceName.Mix(name)
    name == null -> SourceName.Unknown
    else -> SourceName.Named(sourceKindOf(source), name)
}

/** docs/29 §9 F: `PLAYING FROM │ {source}`; the label is cased up, the name stays verbatim. `null` when unknown. */
internal fun playingFromLine(name: SourceName, strings: UiStrings): String? {
    val label = strings.get(R.string.music_playing_from).uppercaseUi()
    return when (name) {
        is SourceName.Named -> "$label │ ${name.name}"
        is SourceName.Mix -> "$label │ " + listOfNotNull(strings.get(R.string.music_instant_mix).uppercaseUi(), name.seed).joinToString(" · ")
        is SourceName.Fixed -> "$label │ ${strings.get(name.title).uppercaseUi()}"
        SourceName.Unknown -> null
    }
}

/** A Queue screen tag for a user-queued row. */
internal enum class QueueTag { PlayNext, Added }

/** One Queue screen line: a group header, or a row at timeline [index] and play [position]. */
internal sealed interface QueueRow {
    data class Header(val text: String) : QueueRow
    data class Track(val index: Int, val position: Int, val entry: QueueEntry, val tag: QueueTag?, val isCurrent: Boolean, val key: String) : QueueRow
}

/**
 * docs/29 §9 H: an open queue menu read against the live [rows] by its row [key], so a row that started playing
 * loses Play next · Move · Remove and a shifted row acts at its new index; `null` once the row has left the queue.
 */
internal fun liveQueueTarget(target: MusicMenuTarget, key: String?, rows: List<QueueRow>, onCurrentAccount: Boolean): MusicMenuTarget? {
    if (target.context !is MusicMenuContext.InQueue) return target
    val row = rows.firstOrNull { it is QueueRow.Track && it.key == key } as? QueueRow.Track ?: return null
    return queueMenuTarget(target.card, row, onCurrentAccount)
}

/** The menu on queue [row]'s [song]; `null` for the playing row of a parked session, which has no row left to offer. */
internal fun queueMenuTarget(song: Card, row: QueueRow.Track, onCurrentAccount: Boolean): MusicMenuTarget? =
    if (row.isCurrent && !onCurrentAccount) null else MusicMenuTarget(song, MusicMenuKind.Song, MusicMenuContext.InQueue(row.index, row.position, row.isCurrent, onCurrentAccount))

/** docs/29 §9 H1: the left column's counts and toggles plus the right list's three groups. */
internal data class QueueScreenModel(
    val countLine: String,
    val rows: List<QueueRow>,
    /** Clear queue is absent when nothing follows the current song. */
    val hasFollowing: Boolean,
    val shuffleLine: String,
    val repeatLine: String,
)

internal fun queueScreenModel(now: MusicNow, name: SourceName, card: (String) -> Card?, strings: UiStrings): QueueScreenModel {
    val groups = queueGroups(now.playOrder, now.current, now.queue.map { it.origin })
    val upcoming = groups.nextInQueue + groups.nextFromSource
    val position = now.playOrder.indexOf(now.current)
    val keys = entryKeys(now.queue)
    fun row(index: Int, at: Int) = QueueRow.Track(index, at, now.queue[index], tagOf(now.queue[index].origin), index == now.current, keys[index])
    val rows = buildList {
        add(QueueRow.Header(strings.get(R.string.music_now_playing).uppercaseUi()))
        add(row(now.current, position))
        if (groups.nextInQueue.isNotEmpty()) {
            add(QueueRow.Header(strings.get(R.string.music_next_in_queue, groups.nextInQueue.size, kindWord(name, strings)).uppercaseUi()))
            groups.nextInQueue.forEachIndexed { i, index -> add(row(index, position + 1 + i)) }
        }
        if (groups.nextFromSource.isNotEmpty()) {
            add(QueueRow.Header(nextFromHeader(name, groups.nextFromSource.size, now.shuffle, strings)))
            groups.nextFromSource.forEachIndexed { i, index -> add(row(index, position + 1 + groups.nextInQueue.size + i)) }
        }
    }
    val leftMs = (trackDurationMs(card(now.queue[now.current].item), playerMs = now.durationMs) - now.positionMs).coerceAtLeast(0L) +
        upcoming.sumOf { durationMs(card(now.queue[it].item)) ?: 0L }
    val songs = upcoming.size + 1
    val count = listOfNotNull(
        strings.plural(R.plurals.music_count_songs, songs, songs.toString()),
        musicLength(leftMs * TICKS_PER_MS, strings)?.let { strings.get(R.string.music_left, it) },
    ).joinToString(" │ ").uppercaseUi()
    val repeat = when (now.repeat) {
        RepeatMode.Off -> R.string.music_off
        RepeatMode.All -> R.string.music_repeat_all
        RepeatMode.One -> R.string.music_repeat_one
    }
    return QueueScreenModel(
        countLine = count,
        rows = rows,
        hasFollowing = upcoming.isNotEmpty(),
        shuffleLine = strings.get(R.string.music_shuffle_state, strings.get(if (now.shuffle) R.string.music_on else R.string.music_off)).uppercaseUi(),
        repeatLine = strings.get(R.string.music_repeat_state, strings.get(repeat)).uppercaseUi(),
    )
}

/** docs/29 §9 H: Repeat steps Off, All, One, then Off. */
internal fun nextRepeat(mode: RepeatMode): RepeatMode = when (mode) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}

/** [now] as the player will hold it after [move]: the entry moved and retagged, the play order set. */
internal fun applyReorder(now: MusicNow, move: QueueReorder): MusicNow {
    val queue = now.queue.toMutableList()
    queue.add(move.to, queue.removeAt(move.from).copy(origin = move.origin))
    return now.copy(
        queue = queue,
        playOrder = move.order ?: queue.indices.toList(),
        current = movedIndex(now.current, move.from, move.to),
    )
}

/**
 * Stable list keys for [queue]: the item with its occurrence, so a song queued twice keeps two rows.
 * The occurrence counts in timeline order, so it is stable across moves that keep duplicates' relative order.
 */
internal fun entryKeys(queue: List<QueueEntry>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return queue.map { entry ->
        val n = seen.merge(entry.item, 1, Int::plus)!!
        "${entry.item}#$n"
    }
}

private fun tagOf(origin: QueueOrigin): QueueTag? = when (origin) {
    QueueOrigin.UserNext -> QueueTag.PlayNext
    QueueOrigin.UserAdded -> QueueTag.Added
    QueueOrigin.Source -> null
}

private fun kindWord(name: SourceName, strings: UiStrings): String = strings.get(
    when ((name as? SourceName.Named)?.kind) {
        SourceKind.Album -> R.string.music_source_album
        SourceKind.Artist -> R.string.music_source_artist
        SourceKind.Playlist -> R.string.music_source_playlist
        SourceKind.List, null -> if (name is SourceName.Mix) R.string.music_source_mix else R.string.music_source_list
    },
)

/** docs/29 §9 H1: `NEXT FROM {source} · n` (`· SHUFFLED ORDER`); the name stays verbatim. */
private fun nextFromHeader(name: SourceName, count: Int, shuffled: Boolean, strings: UiStrings): String {
    val label = strings.get(R.string.music_next_from).uppercaseUi()
    val source = when (name) {
        is SourceName.Named -> name.name
        is SourceName.Mix -> strings.get(R.string.music_instant_mix).uppercaseUi()
        is SourceName.Fixed -> strings.get(name.title).uppercaseUi()
        SourceName.Unknown -> null
    }
    val order = strings.get(R.string.music_shuffled_order).uppercaseUi().takeIf { shuffled }
    return listOfNotNull(listOfNotNull(label, source).joinToString(" "), count.toString(), order).joinToString(" · ")
}

private fun durationMs(card: Card?): Long? = trackDurationMs(card).takeIf { it > 0L }

private const val TICKS_PER_MS = 10_000L
