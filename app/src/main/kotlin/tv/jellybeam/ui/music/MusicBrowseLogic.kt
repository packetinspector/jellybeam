package tv.jellybeam.ui.music

import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.mediumDateFormatter
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicSource
import uniffi.jellybeam_core.AlbumSummary
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.MusicArtistRow
import uniffi.jellybeam_core.SongSource
import uniffi.jellybeam_core.ViewSnapshot

/** docs/29 §9 A: the landing's tabs, in their fixed order; [itemType] is what each lists. */
internal enum class MusicTab(val itemType: String) {
    Albums("MusicAlbum"),
    Artists("MusicArtist"),
    Songs("Audio"),
    Playlists("Playlist"),
    Genres("MusicGenre"),
}

/** The tab a persisted item type names, `null` for none or an unknown one. */
internal fun musicTabForItemType(itemType: String?): MusicTab? = MusicTab.entries.firstOrNull { it.itemType == itemType }

/** docs/29 §9 A: Playlists shows only once the server's Playlists view exists or any playlist does. */
internal fun visibleMusicTabs(hasPlaylists: Boolean, playlistsViewExists: Boolean): List<MusicTab> =
    MusicTab.entries.filter { it != MusicTab.Playlists || hasPlaylists || playlistsViewExists }

/** The server's Playlists view, by its collection type. */
internal fun playlistsViewExists(views: List<ViewSnapshot>): Boolean = views.any { it.collectionType == "playlists" }

/** docs/29 §9 A: the last-used tab while it still shows, else Albums. */
internal fun openingMusicTab(saved: MusicTab?, visible: List<MusicTab>): MusicTab = saved?.takeIf { it in visible } ?: MusicTab.Albums

/** Whether a library is browsed with the music screens (docs/29 §9 A entry). */
internal val ViewSnapshot.isMusicLibrary: Boolean get() = collectionType == "music"

/** One row of the album page's track list: a disc header or a track at its queue [index]. */
internal sealed interface AlbumListEntry {
    data class Disc(val number: Int) : AlbumListEntry
    data class Track(val card: Card, val index: Int) : AlbumListEntry
}

/**
 * docs/29 §9 C: disc headers only when the tracks span more than one distinct ParentIndexNumber;
 * a track with none stays under the header before it. Every track keeps its queue index.
 */
internal fun albumListEntries(tracks: List<Card>): List<AlbumListEntry> {
    val multiDisc = tracks.mapNotNull { it.parentIndexNumber }.distinct().size > 1
    val entries = ArrayList<AlbumListEntry>(tracks.size + 4)
    var disc: Int? = null
    tracks.forEachIndexed { index, track ->
        val number = track.parentIndexNumber
        if (multiDisc && number != null && number != disc) {
            entries += AlbumListEntry.Disc(number)
            disc = number
        }
        entries += AlbumListEntry.Track(track, index)
    }
    return entries
}

/** docs/29 §9 C: a compilation's rows each show their own performers; an album with one artist shows none. */
internal fun isCompilation(albumArtist: String?, tracks: List<Card>): Boolean =
    tracks.any { musicArtistLine(it) != albumArtist.orEmpty() }

/** Where the album page opens focus (docs/29 §9 C1): the playing track's row when it is on this album, else Play. */
internal sealed interface AlbumOpenFocus {
    data object Play : AlbumOpenFocus
    data class Track(val itemId: String) : AlbumOpenFocus
}

internal fun albumOpenFocus(playingItemId: String?, tracks: List<Card>): AlbumOpenFocus =
    if (playingItemId != null && tracks.any { it.id == playingItemId }) AlbumOpenFocus.Track(playingItemId) else AlbumOpenFocus.Play

/** A song list row's line 2 (docs/29 §9 D1b). */
internal enum class SongLine2 { ArtistAlbum, AlbumOnly }

/** A song list's right-hand column before Time. */
internal enum class SongExtra { Added, Played, Year }

/** One song list's column set (docs/29 §9 D1b); [rail] is the All songs A–Z rail, [plays] the PLAYS column. */
internal data class SongColumns(val line2: SongLine2, val extra: SongExtra?, val rail: Boolean, val plays: Boolean)

internal fun songColumns(source: SongSource): SongColumns = when (source) {
    is SongSource.All -> SongColumns(SongLine2.ArtistAlbum, extra = null, rail = true, plays = true)
    is SongSource.Favorites -> SongColumns(SongLine2.ArtistAlbum, SongExtra.Added, rail = false, plays = false)
    is SongSource.RecentlyPlayed -> SongColumns(SongLine2.ArtistAlbum, SongExtra.Played, rail = false, plays = true)
    is SongSource.Artist, is SongSource.Genre -> SongColumns(SongLine2.AlbumOnly, SongExtra.Year, rail = false, plays = true)
}

/** docs/29 §9 A3/D1: the PLAYS column, grouped, or a dash for a song never played. */
internal fun playsLabel(card: Card): String = if (card.playCount == 0u) "—" else groupedCount(card.playCount.toLong())

/** docs/29 §9 B1: Most played's `n PLAYS`; cased up. */
internal fun mostPlayedCount(card: Card, strings: UiStrings): String =
    strings.plural(R.plurals.music_count_plays, card.playCount.toInt(), groupedCount(card.playCount.toLong())).uppercaseUi()

/** A song list row (docs/29 §9 D): two-line with its thumb; [extra] is cased up, names stay verbatim. */
internal fun songRowData(card: Card, columns: SongColumns, strings: UiStrings, nowMs: Long, zone: ZoneId, number: String? = null): TrackRowData {
    val album = card.album
    val line2 = when (columns.line2) {
        SongLine2.AlbumOnly -> album
        SongLine2.ArtistAlbum -> listOfNotNull(musicArtistLine(card).ifEmpty { null }, album).joinToString(" · ").ifEmpty { null }
    }
    val extra = when (columns.extra) {
        null -> null
        SongExtra.Year -> card.productionYear?.toString()
        SongExtra.Added -> card.dateCreated?.let { parseInstant(it) }?.let { strings.get(R.string.music_added_on, shortDate(it, zone)) }
        SongExtra.Played -> card.lastPlayedDate?.let { parseInstant(it) }?.let { playedAgo(it, nowMs, zone, strings) }
    }
    return TrackRowData(
        title = card.name,
        duration = trackDurationLabel(card),
        number = number,
        thumb = trackArtSource(card),
        line2 = line2,
        extra = extra?.uppercaseUi(),
        plays = playsLabel(card).takeIf { columns.plays },
        hasLyrics = card.hasLyrics,
        isFavorite = card.isFavorite,
    )
}

/** docs/29 §9 D1: under an hour in minutes, under a day in hours, then Yesterday, days within a week, else the date. */
internal fun playedAgo(at: Instant, nowMs: Long, zone: ZoneId, strings: UiStrings): String {
    val minutes = ((nowMs - at.toEpochMilli()) / 60_000L).coerceAtLeast(0L)
    if (minutes < 60) return strings.get(R.string.music_ago_minutes, minutes.toInt())
    if (minutes < 24 * 60) return strings.get(R.string.music_ago_hours, (minutes / 60).toInt())
    val days = (Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay() - at.atZone(zone).toLocalDate().toEpochDay()).toInt()
    return when {
        days <= 1 -> strings.get(R.string.music_ago_yesterday)
        days < 7 -> strings.plural(R.plurals.music_ago_days, days, days)
        else -> shortDate(at, zone)
    }
}

/** "Oct 29": month and day in the device locale. */
private fun shortDate(at: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("MMM d", AppLocale.format).format(at.atZone(zone))

/** "Oct 1, 2026" in the device locale's own field order. */
internal fun mediumDate(iso: String?, zone: ZoneId): String? =
    iso?.let(::parseInstant)?.let { mediumDateFormatter(AppLocale.format).format(it.atZone(zone)) }

/** The core's RFC 3339 dates end in `+00:00`, which older Android java.time `Instant.parse` (OpenJDK 8, `Z` only) refuses; the offset parser takes both. */
private fun parseInstant(iso: String): Instant? = try {
    OffsetDateTime.parse(iso).toInstant()
} catch (e: java.time.format.DateTimeParseException) {
    null
}

/** `19 min`, `2 h 51 min`; `null` for an unknown length. */
internal fun musicLength(ticks: Long?, strings: UiStrings): String? {
    if (ticks == null || ticks <= 0L) return null
    val minutes = ((ticks / TICKS_PER_MS + 30_000L) / 60_000L).toInt()
    return if (minutes < 60) strings.get(R.string.music_minutes, minutes) else strings.get(R.string.music_hours_minutes, minutes / 60, minutes % 60)
}

/** A count with the locale's grouping, "1,186". */
internal fun groupedCount(n: Long, locale: Locale = AppLocale.format): String = NumberFormat.getIntegerInstance(locale).format(n)

/**
 * docs/29 §9 A sort strip: `{n} {TYPE} │ {SORT}`, the order omitted while the tab is empty; cased up.
 * A `null` [count] is a list whose total isn't known yet (live playlists), so only the order shows.
 */
internal fun musicSortStrip(tab: MusicTab, count: Long?, strings: UiStrings): String {
    val order = strings.get(if (tab == MusicTab.Playlists) R.string.music_sort_recently_changed else R.string.music_sort_name)
    if (count == null) return order.uppercaseUi()
    val n = groupedCount(count)
    val counted = when (tab) {
        MusicTab.Albums -> strings.plural(R.plurals.music_count_albums, count.toInt(), n)
        MusicTab.Artists -> strings.plural(R.plurals.music_count_artists, count.toInt(), n)
        MusicTab.Songs -> strings.plural(R.plurals.music_count_songs, count.toInt(), n)
        MusicTab.Playlists -> strings.plural(R.plurals.music_count_playlists, count.toInt(), n)
        MusicTab.Genres -> strings.plural(R.plurals.music_count_genres, count.toInt(), n)
    }
    if (count == 0L) return counted.uppercaseUi()
    return strings.get(R.string.music_sort_strip, counted, order).uppercaseUi()
}

/** Rows read by [readPages]; [more] when the last page came back full, so the list may go on. */
internal class PagedRows<T>(val rows: List<T>, val more: Boolean)

/**
 * docs/29 §7: up to [want] rows from [from], asked for [MusicLibraryViewModel.PAGE] at a time and stopping at a
 * short page, so a refresh of a long list never becomes one oversized call; `null` when any page can't be read.
 */
internal suspend fun <T> readPages(from: Int, want: Int, page: suspend (offset: UInt, limit: UInt) -> List<T>?): PagedRows<T>? {
    val rows = ArrayList<T>()
    while (rows.size < want) {
        val next = page((from + rows.size).toUInt(), MusicLibraryViewModel.PAGE.toUInt()) ?: return null
        rows += next
        if (next.size < MusicLibraryViewModel.PAGE) return PagedRows(rows, more = false)
    }
    return PagedRows(rows, more = true)
}

/** One mono label over its value in the focused-item facts. */
internal data class MusicFact(val label: String, val value: String)

/** docs/29 §9 A: the focused item's title, subline and facts, top right as on the Films grid. */
internal data class FocusedFacts(val title: String, val subline: String?, val facts: List<MusicFact>)

/** An album card's subline: its album artist and year, either alone when the other is missing. */
internal fun albumSubline(card: Card, strings: UiStrings): String? {
    val parts = listOfNotNull(card.albumArtist?.ifEmpty { null }, card.productionYear?.toString())
    return when (parts.size) {
        0 -> null
        1 -> parts[0]
        else -> strings.get(R.string.music_pair, parts[0], parts[1])
    }
}

/** Albums tab facts: tracks and length once the summary is read, and when the server added it. */
internal fun albumFacts(card: Card, summary: AlbumSummary?, strings: UiStrings, zone: ZoneId): FocusedFacts {
    val facts = buildList {
        summary?.let { add(MusicFact(strings.get(R.string.music_fact_tracks).uppercaseUi(), it.trackCount.toString())) }
        musicLength(summary?.runtimeTicks, strings)?.let { add(MusicFact(strings.get(R.string.music_fact_length).uppercaseUi(), it)) }
        card.dateCreated?.let(::parseInstant)?.let { add(MusicFact(strings.get(R.string.music_fact_added).uppercaseUi(), shortDate(it, zone))) }
        if (card.playCount > 0u) {
            add(MusicFact(strings.get(R.string.music_fact_played).uppercaseUi(), strings.plural(R.plurals.music_played_times, card.playCount.toInt(), groupedCount(card.playCount.toLong()))))
        }
    }
    return FocusedFacts(card.name, albumSubline(card, strings), facts)
}

/** Artists tab facts: albums (or the albums it appears on), songs and length. */
internal fun artistFacts(row: MusicArtistRow, strings: UiStrings): FocusedFacts {
    val facts = buildList {
        if (row.albumCount > 0uL) {
            add(MusicFact(strings.get(R.string.music_fact_albums).uppercaseUi(), row.albumCount.toString()))
        } else if (row.appearsOnCount > 0uL) {
            add(MusicFact(strings.get(R.string.music_fact_appears_on).uppercaseUi(), row.appearsOnCount.toString()))
        }
        add(MusicFact(strings.get(R.string.music_fact_songs).uppercaseUi(), row.songCount.toString()))
        musicLength(row.runtimeTicks, strings)?.let { add(MusicFact(strings.get(R.string.music_fact_length).uppercaseUi(), it)) }
    }
    return FocusedFacts(row.name, null, facts)
}

/** docs/29 §8.3: `n ALBUMS`, or `APPEARS ON n` for a per-track performer; cased up by the circle. */
internal fun artistCircleSubline(row: MusicArtistRow, strings: UiStrings): String? = when {
    row.albumCount > 0uL -> strings.plural(R.plurals.music_count_albums, row.albumCount.toInt(), row.albumCount.toString())
    row.appearsOnCount > 0uL -> strings.get(R.string.music_appears_on_n, row.appearsOnCount.toString())
    else -> null
}

/** docs/29 §9 B header: `n ALBUMS │ n SONGS │ n MIN`, or `APPEARS ON n ALBUM │ …` for a per-track performer; cased up. */
internal fun artistHeaderLine(row: MusicArtistRow, strings: UiStrings): String {
    val albums = if (row.albumCount > 0uL || row.appearsOnCount == 0uL) {
        strings.plural(R.plurals.music_count_albums, row.albumCount.toInt(), row.albumCount.toString())
    } else {
        strings.plural(R.plurals.music_appears_on_albums, row.appearsOnCount.toInt(), row.appearsOnCount.toString())
    }
    val parts = listOfNotNull(
        albums,
        strings.plural(R.plurals.music_count_songs, row.songCount.toInt(), row.songCount.toString()),
        musicLength(row.runtimeTicks, strings),
    )
    return parts.joinToString(" │ ").uppercaseUi()
}

/** docs/29 §9 C header: `year │ n TRACKS │ n MIN`, missing parts omitted; cased up. */
internal fun albumHeaderLine(year: Int?, summary: AlbumSummary?, strings: UiStrings): String = listOfNotNull(
    year?.toString(),
    summary?.let { strings.plural(R.plurals.music_count_tracks, it.trackCount.toInt(), it.trackCount.toString()) },
    musicLength(summary?.runtimeTicks, strings),
).joinToString(" │ ").uppercaseUi()

/** docs/29 §9 E1 header: `n SONGS │ n MIN │ n ALBUMS` over the loaded rows; cased up. */
internal fun playlistHeaderLine(songs: List<Card>, strings: UiStrings): String {
    val ticks = songs.sumOf { it.runtimeTicks ?: 0L }
    val albums = songs.mapNotNull { it.albumId }.distinct().size
    return listOfNotNull(
        strings.plural(R.plurals.music_count_songs, songs.size, songs.size.toString()),
        musicLength(ticks, strings),
        albums.takeIf { it > 0 }?.let { strings.plural(R.plurals.music_count_albums, it, it.toString()) },
    ).joinToString(" │ ").uppercaseUi()
}

/** What the page shows instead of its list: a tab or song list with nothing in it (docs/29 §10 S2). */
internal fun musicEmptyLine(tab: MusicTab): Int = when (tab) {
    MusicTab.Playlists -> R.string.music_empty_playlists
    MusicTab.Albums, MusicTab.Artists, MusicTab.Songs, MusicTab.Genres -> R.string.music_empty_library
}

internal fun songListEmptyLine(source: SongSource): Int = when (source) {
    is SongSource.Favorites -> R.string.music_empty_favorites
    is SongSource.RecentlyPlayed -> R.string.music_empty_played
    is SongSource.All, is SongSource.Artist, is SongSource.Genre -> R.string.music_empty_library
}

/** Rows a track list reserves while loading (docs/29 §10 S1): the known count, else 6. */
internal fun skeletonRowCount(known: ULong?): Int = known?.toInt()?.takeIf { it > 0 }?.coerceAtMost(12) ?: 6

/** A stable string for a song source, for its back-stack identity. */
internal fun songSourceKey(source: SongSource): String = when (source) {
    is SongSource.All -> "all-${source.viewId}"
    is SongSource.Favorites -> "fav-${source.viewId}"
    is SongSource.RecentlyPlayed -> "recent-${source.viewId}"
    is SongSource.Genre -> "genre-${source.viewId}-${source.genre}"
    is SongSource.Artist -> "artist-${source.artistId}"
}

/** What the remote's Play key does on a Home album card (docs/29 §9 J1). */
internal enum class AlbumPlayKey { Play, Pause, Resume }

/** docs/29 §9 J1: Play on the album that is already the playing source pauses or resumes it rather than restarting. */
internal fun albumPlayKey(flow: MusicFlow?, isPlaying: Boolean, albumId: String): AlbumPlayKey {
    val source = when (val phase = flow?.phase) {
        null, MusicFlow.Phase.Idle -> null
        is MusicFlow.Phase.Starting -> phase.session.source
        is MusicFlow.Phase.Active -> phase.session.source
    }
    return when {
        source != MusicSource.Album(albumId) -> AlbumPlayKey.Play
        isPlaying -> AlbumPlayKey.Pause
        else -> AlbumPlayKey.Resume
    }
}

/** The system zone, read where a formatter needs one. */
internal fun deviceZone(): ZoneId = ZoneId.systemDefault()

private const val TICKS_PER_MS = 10_000L

/** The server writes one id with or without dashes and in either case (a create's reply vs. a list's rows), so ids compare on their characters past the dashes. */
internal fun sameItemId(a: String, b: String): Boolean = a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)

/** docs/15 §3: the key that takes a removed row's focus: the next row of [before] still in [after], else the previous one; null once none is left. */
internal fun keyAfterRemoval(before: List<String>, removed: String, after: List<String>): String? {
    val at = before.indexOf(removed)
    val kept = after.toHashSet()
    if (at < 0) return after.firstOrNull()
    return before.subList(at + 1, before.size).firstOrNull { it in kept } ?: before.subList(0, at).lastOrNull { it in kept }
}

/**
 * docs/29 §9 E1: one row key per playlist entry; the server gives a song added twice the same entry id, so a
 * repeat takes `#n`, keeping the list's keys unique.
 */
internal fun playlistRowKeys(entryIds: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return entryIds.map { id ->
        val n = (seen[id] ?: 0) + 1
        seen[id] = n
        if (n == 1) "entry:$id" else "entry:$id#$n"
    }
}
