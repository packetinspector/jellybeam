package tv.jellybeam.ui.music

import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.EnqueueMode
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicNotice
import tv.jellybeam.player.music.MusicSource
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.MixSeedFfi

/**
 * Pure content model for the music ··· menu (docs/29 §9 L, §8.7), the docs/19 discipline: [buildMusicMenu]
 * decides every row and the shared docs/19 panel only renders it. Rows are absent, never disabled.
 */

/** docs/29 §9 L1: the four item kinds a menu opens on. */
internal enum class MusicMenuKind { Song, Album, Artist, Playlist }

/** The kind a card's server type names; `null` for anything that has no music menu. */
internal fun musicMenuKind(card: Card): MusicMenuKind? = when (card.itemType) {
    "Audio" -> MusicMenuKind.Song
    "MusicAlbum" -> MusicMenuKind.Album
    MUSIC_ARTIST_TYPE -> MusicMenuKind.Artist
    "Playlist" -> MusicMenuKind.Playlist
    else -> null
}

/** Where a song's menu opened: a playlist page knows the row's entry, the queue its row; else neither. */
internal sealed interface MusicMenuContext {
    data object Browse : MusicMenuContext

    /** docs/29 §9 L1: a song opened inside playlist [playlistId] as entry [entryId] can be removed from it. */
    data class InPlaylist(val playlistId: String, val entryId: String) : MusicMenuContext

    /**
     * docs/29 §9 H: queue row at timeline [index]; the playing row offers only Go to album. A session on a parked
     * account ([onCurrentAccount] false) offers no Go to album, which would open on the current account.
     */
    data class InQueue(val index: Int, val position: Int, val isCurrent: Boolean, val onCurrentAccount: Boolean) : MusicMenuContext

    /** The playing song on Now Playing: it is already the queue, so no enqueue rows. */
    data object NowPlaying : MusicMenuContext
}

internal data class MusicMenuTarget(val card: Card, val kind: MusicMenuKind, val context: MusicMenuContext = MusicMenuContext.Browse)

/** A menu for [card] opened outside a playlist or the queue; `null` when it isn't a music item. */
internal fun musicMenuTarget(card: Card): MusicMenuTarget? = musicMenuKind(card)?.let { MusicMenuTarget(card, it) }

/**
 * The Now Playing menu on [card], also the condition for its heart; `null` with no card or session yet, or while the
 * session's [sessionEpoch] is a parked account, since every row and the heart act on the [currentEpoch] account.
 */
internal fun nowPlayingMenuTarget(card: Card?, sessionEpoch: ULong?, currentEpoch: ULong): MusicMenuTarget? =
    if (card == null || sessionEpoch != currentEpoch) null else MusicMenuTarget(card, MusicMenuKind.Song, MusicMenuContext.NowPlaying)

internal sealed interface MusicMenuAction {
    data object AddFavorite : MusicMenuAction
    data object RemoveFavorite : MusicMenuAction
    data class GoToAlbum(val albumId: String) : MusicMenuAction
    data class GoToArtist(val artistId: String) : MusicMenuAction
    data object ReorderSongs : MusicMenuAction
    data object PlayNext : MusicMenuAction
    data object AddToQueue : MusicMenuAction
    data object InstantMix : MusicMenuAction
    data object AddToPlaylist : MusicMenuAction
    data object RefreshMetadata : MusicMenuAction
    data class RemoveFromPlaylist(val playlistId: String, val entryId: String) : MusicMenuAction
    data object Move : MusicMenuAction
    data object Remove : MusicMenuAction
}

/** [drillIn] rows open a second level (`›`). */
internal data class MusicMenuRow(val action: MusicMenuAction, val label: String, val drillIn: Boolean = false)

/** One always-open group under its mono heading. */
internal data class MusicMenuGroup(val heading: String, val rows: List<MusicMenuRow>)

internal data class MusicMenuModel(val groups: List<MusicMenuGroup>) {
    val rows: List<MusicMenuRow> get() = groups.flatMap { it.rows }
}

/** docs/29 §9 L1: a menu never holds more rows than this, so it never scrolls. */
internal const val MUSIC_MENU_MAX_ROWS = 8

/** Refresh metadata stays the docs/19 §1.1 administrator row; Reorder songs exists only where the page can reorder. */
internal data class MusicMenuInput(
    val target: MusicMenuTarget,
    val isFavorite: Boolean,
    val isAdministrator: Boolean,
    val canReorder: Boolean = false,
)

/** docs/29 §9 L1/§H: the rows [input] warrants, in the three fixed groups; Watch together never appears (Q4). */
internal fun buildMusicMenu(strings: UiStrings, input: MusicMenuInput): MusicMenuModel {
    val target = input.target
    val card = target.card
    val context = target.context
    val favorite = if (input.isFavorite) {
        MusicMenuRow(MusicMenuAction.RemoveFavorite, strings.get(R.string.detail_menu_remove_favorite))
    } else {
        MusicMenuRow(MusicMenuAction.AddFavorite, strings.get(R.string.detail_menu_add_favorite))
    }
    val goToAlbum = card.albumId?.let { MusicMenuRow(MusicMenuAction.GoToAlbum(it), strings.get(R.string.music_go_to_album)) }
    val goToArtist = card.albumArtistId?.let { MusicMenuRow(MusicMenuAction.GoToArtist(it), strings.get(R.string.music_go_to_artist)) }
    val refresh = MusicMenuRow(MusicMenuAction.RefreshMetadata, strings.get(R.string.detail_menu_refresh_metadata)).takeIf { input.isAdministrator }
    val addToPlaylist = MusicMenuRow(MusicMenuAction.AddToPlaylist, strings.get(R.string.music_add_to_playlist), drillIn = true)

    if (context is MusicMenuContext.InQueue) {
        val playback = if (context.isCurrent) {
            emptyList()
        } else {
            listOf(
                MusicMenuRow(MusicMenuAction.PlayNext, strings.get(R.string.music_play_next)),
                MusicMenuRow(MusicMenuAction.Move, strings.get(R.string.music_move)),
                MusicMenuRow(MusicMenuAction.Remove, strings.get(R.string.music_remove)),
            )
        }
        return menuOf(strings, MusicMenuKind.Song, listOfNotNull(goToAlbum.takeIf { context.onCurrentAccount }), playback, emptyList())
    }

    val instantMix = MusicMenuRow(MusicMenuAction.InstantMix, strings.get(R.string.music_instant_mix))
    val playback = if (context is MusicMenuContext.NowPlaying) {
        listOf(instantMix)
    } else {
        listOf(
            MusicMenuRow(MusicMenuAction.PlayNext, strings.get(R.string.music_play_next)),
            MusicMenuRow(MusicMenuAction.AddToQueue, strings.get(R.string.music_add_to_queue)),
            instantMix,
        )
    }
    val (thisItem, library) = when (target.kind) {
        MusicMenuKind.Song -> listOfNotNull(favorite, goToAlbum, goToArtist) to listOfNotNull(
            addToPlaylist,
            (context as? MusicMenuContext.InPlaylist)?.let {
                MusicMenuRow(MusicMenuAction.RemoveFromPlaylist(it.playlistId, it.entryId), strings.get(R.string.music_remove_from_playlist))
            },
        )
        MusicMenuKind.Album -> listOfNotNull(favorite, goToArtist) to listOfNotNull(addToPlaylist, refresh)
        MusicMenuKind.Artist -> listOf(favorite) to listOfNotNull(refresh)
        MusicMenuKind.Playlist -> listOfNotNull(
            favorite,
            MusicMenuRow(MusicMenuAction.ReorderSongs, strings.get(R.string.music_reorder_songs)).takeIf { input.canReorder },
        ) to emptyList()
    }
    return menuOf(strings, target.kind, thisItem, playback, library)
}

private fun menuOf(strings: UiStrings, kind: MusicMenuKind, thisItem: List<MusicMenuRow>, playback: List<MusicMenuRow>, library: List<MusicMenuRow>): MusicMenuModel {
    val thisHeading = when (kind) {
        MusicMenuKind.Song -> R.string.music_menu_this_song
        MusicMenuKind.Album -> R.string.music_menu_this_album
        MusicMenuKind.Artist -> R.string.music_menu_this_artist
        MusicMenuKind.Playlist -> R.string.music_menu_this_playlist
    }
    val groups = listOf(
        thisHeading to thisItem,
        R.string.detail_menu_group_playback to playback,
        R.string.detail_menu_group_library to library,
    ).filter { it.second.isNotEmpty() }.map { (heading, rows) -> MusicMenuGroup(strings.get(heading).uppercaseUi(), rows) }
    return MusicMenuModel(groups)
}

/** docs/29 §9 E2: one row of the Add to playlist level. */
internal sealed interface PlaylistPick {
    data object New : PlaylistPick

    /** [inPlaylist]: it already holds the song; choosing it still adds (Jellyfin allows repeats). */
    data class Existing(val playlist: Card, val inPlaylist: Boolean) : PlaylistPick
    data object All : PlaylistPick
}

/** docs/29 §9 E2: the panel shows this many recently changed playlists, then All playlists…, so it never scrolls. */
internal const val RECENT_PLAYLISTS = 7

/**
 * docs/29 §9 E2: New playlist first, then the [RECENT_PLAYLISTS] most recently changed of [recent] (server
 * order, fetched one past the limit), then All playlists… only when there are more; [holding] names those
 * that already hold the song.
 */
internal fun playlistPicks(recent: List<Card>, holding: Set<String>): List<PlaylistPick> = buildList {
    add(PlaylistPick.New)
    recent.take(RECENT_PLAYLISTS).forEach { add(PlaylistPick.Existing(it, it.id in holding)) }
    if (recent.size > RECENT_PLAYLISTS) add(PlaylistPick.All)
}

/** docs/29 §9 E3: Create and add is absent while the name is empty. */
internal fun canCreatePlaylist(name: String): Boolean = name.isNotBlank()

/** docs/29 §9 I1: a mix that answers faster than this opens without dots. */
internal const val MIX_DOTS_AFTER_MS = 400L

/** What a control that started an Instant Mix shows, read from the owner's pending request. */
internal sealed interface MixShown {
    data object Idle : MixShown

    /** The request is in flight but younger than [MIX_DOTS_AFTER_MS]: nothing changes yet. */
    data object Waiting : MixShown

    /** The pressed control swaps its glyph for the loading dots. */
    data object Building : MixShown

    /** The mix was empty or failed (one line for both, in GRIGIO): [line] names the seed's type. */
    data class NoMix(val line: Int) : MixShown
}

/** docs/29 §9 I1/I2: [pending] is the source of truth; [waitedMs] is how long the current fetch has been seen. */
internal fun mixShown(pending: MusicFlow.Pending, seed: MixSeedFfi, waitedMs: Long): MixShown {
    val mine = MusicSource.Mix(seed)
    return when (pending) {
        is MusicFlow.Pending.Fetch -> when {
            pending.source != mine -> MixShown.Idle
            waitedMs >= MIX_DOTS_AFTER_MS -> MixShown.Building
            else -> MixShown.Waiting
        }
        is MusicFlow.Pending.Refused -> if (pending.source == mine) MixShown.NoMix(noMixLine(seed)) else MixShown.Idle
        MusicFlow.Pending.None -> MixShown.Idle
    }
}

/** docs/29 §9 I1: [after] holds a session newly minted from an Instant Mix, so Now Playing opens on it. */
internal fun mixStarted(before: MusicFlow, after: MusicFlow): Boolean {
    if (after.lastSid == before.lastSid) return false
    val session = when (val phase = after.phase) {
        MusicFlow.Phase.Idle -> return false
        is MusicFlow.Phase.Starting -> phase.session
        is MusicFlow.Phase.Active -> phase.session
    }
    return session.sid == after.lastSid && session.source is MusicSource.Mix
}

/** The request id of [seed]'s mix while it is in flight; Back cancels it. */
internal fun mixRequest(pending: MusicFlow.Pending, seed: MixSeedFfi): Long? =
    (pending as? MusicFlow.Pending.Fetch)?.takeIf { it.source == MusicSource.Mix(seed) }?.r

private fun noMixLine(seed: MixSeedFfi): Int = when (seed) {
    is MixSeedFfi.Song -> R.string.music_no_mix_song
    is MixSeedFfi.Album -> R.string.music_no_mix_album
    is MixSeedFfi.Artist -> R.string.music_no_mix_artist
    is MixSeedFfi.Playlist -> R.string.music_no_mix_playlist
    is MixSeedFfi.Genre -> R.string.music_no_mix_genre
}

/** docs/29 §9 I: the endpoint a menu target seeds its mix from. */
internal fun mixSeed(target: MusicMenuTarget): MixSeedFfi = when (target.kind) {
    MusicMenuKind.Song -> MixSeedFfi.Song(target.card.id)
    MusicMenuKind.Album -> MixSeedFfi.Album(target.card.id)
    MusicMenuKind.Artist -> MixSeedFfi.Artist(target.card.id)
    MusicMenuKind.Playlist -> MixSeedFfi.Playlist(target.card.id)
}

/** What Play next and Add to queue fetch for [target]: a song plays with the metadata it was opened with. */
internal fun enqueueSource(target: MusicMenuTarget): MusicSource = when (target.kind) {
    MusicMenuKind.Song -> MusicSource.Single(target.card)
    MusicMenuKind.Album -> MusicSource.Album(target.card.id)
    MusicMenuKind.Artist -> MusicSource.Artist(target.card.id)
    MusicMenuKind.Playlist -> MusicSource.Playlist(target.card.id)
}

/** docs/29 §8.4: the bar's line 2 for [notice]; the mono label is cased up, a name follows verbatim. */
internal fun musicNoticeText(notice: MusicNotice, strings: UiStrings): String = when (notice) {
    is MusicNotice.Queued -> {
        val label = if (notice.mode == EnqueueMode.PlayNext) R.string.music_notice_playing_next else R.string.music_notice_added_to_queue
        "${strings.get(label).uppercaseUi()} │ ${notice.title}"
    }
    is MusicNotice.AddedToPlaylist ->
        if (notice.again) strings.get(R.string.music_notice_added_again).uppercaseUi() else "${strings.get(R.string.music_notice_added_to).uppercaseUi()} ${notice.playlist}"
    is MusicNotice.PlaylistFailed -> "${strings.get(R.string.music_notice_couldnt_add_to).uppercaseUi()} ${notice.playlist}"
    is MusicNotice.EnqueueFailed -> strings.get(R.string.music_notice_enqueue_failed).uppercaseUi()
    is MusicNotice.StartFailed -> strings.get(R.string.music_notice_start_failed).uppercaseUi()
}
