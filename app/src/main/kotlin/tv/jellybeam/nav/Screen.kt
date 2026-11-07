package tv.jellybeam.nav

import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrBrowseKind
import uniffi.jellybeam_core.SongSource
import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/**
 * A Jellyfin plugin channel's folder item ([Card.itemType]), e.g. a TVHeadend recordings
 * folder inside a `ViewKind.CHANNEL` view. `internal` so [tv.jellybeam.ui.library.ChannelFolderList]
 * can reuse the literal.
 */
internal const val CHANNEL_FOLDER_ITEM_TYPE = "ChannelFolderItem"

/**
 * The app's navigable destinations: one sealed hierarchy over the exact FFI type a screen
 * needs to render itself, so it never re-fetches what its caller already had. Seerr Discover
 * destinations (docs/14-seerr-discover.md) carry only an id, unlike [Detail]'s whole `Card`.
 */
sealed interface Screen {
    data object SignIn : Screen
    data object Home : Screen
    data class Library(val view: ViewSnapshot) : Screen
    data class Detail(val card: Card) : Screen
    /** docs/11 §Person page: a cast/crew member's library page; [name] only titles the loading state. */
    data class Person(val personId: String, val name: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Discover : Screen
    data class DiscoverGrid(
        val kind: SeerrBrowseKind,
        val title: String,
        val genreId: Long? = null,
        val genreName: String? = null,
    ) : Screen
    data class DiscoverDetail(val mediaType: SeerrMediaType, val tmdbId: Long) : Screen
    data class DiscoverPerson(val personId: Long) : Screen
    data object DiscoverRequests : Screen
    data object DiscoverSearch : Screen
    /** docs/21 §1.2: the LAN report page, opened from Settings › Troubleshooting or the
     * post-crash dialog. Singleton, like [Settings]. */
    data object Report : Screen
    /** docs/28 §1: the code entry (no [itemId]) or the host's lobby holding [itemId] until Start; one per stack. */
    data class WatchTogether(val itemId: String?, val resumeTicks: Long?) : Screen
    /** docs/29 §9 A: a music library's landing, opened on its last-used tab. */
    data class MusicLibrary(val view: ViewSnapshot) : Screen
    /** docs/29 §9 C/B: an album or artist page by mirror id. */
    data class MusicAlbum(val albumId: String) : Screen
    data class MusicArtist(val artistId: String) : Screen
    /** docs/29 §9 D: one song list; [title] heads it verbatim. */
    data class MusicSongs(val source: SongSource, val title: String) : Screen
    /** docs/29 §9 E1: a playlist, carried whole since playlists are read live, not from the mirror. */
    data class MusicPlaylist(val playlist: Card) : Screen

    /** docs/29 §9 F/H: the playing session's full screens; one each per stack, with no drawer and no music bar. */
    data object MusicNowPlaying : Screen
    data object MusicQueue : Screen
}

/** docs/29 §8.4: the bar is absent on Now Playing and the Queue. */
val Screen.hidesMusicBar: Boolean get() = this == Screen.MusicNowPlaying || this == Screen.MusicQueue

/**
 * docs/21 §2.1's `nav.screen` `screen` tag: an explicit fixed name per variant, not
 * `::class.simpleName` -- unlike `uniffi.jellybeam_core.**` (kept verbatim by
 * app/proguard-rules.pro for JNA), this sealed interface has no such keep rule, so a release
 * build's R8 pass is free to rename it.
 */
fun Screen.diagName(): String = when (this) {
    Screen.SignIn -> "SignIn"
    Screen.Home -> "Home"
    is Screen.Library -> "Library"
    is Screen.Detail -> "Detail"
    is Screen.Person -> "Person"
    Screen.Search -> "Search"
    Screen.Settings -> "Settings"
    Screen.Discover -> "Discover"
    is Screen.DiscoverGrid -> "DiscoverGrid"
    is Screen.DiscoverDetail -> "DiscoverDetail"
    is Screen.DiscoverPerson -> "DiscoverPerson"
    Screen.DiscoverRequests -> "DiscoverRequests"
    Screen.DiscoverSearch -> "DiscoverSearch"
    Screen.Report -> "Report"
    is Screen.WatchTogether -> "WatchTogether"
    is Screen.MusicLibrary -> "MusicLibrary"
    is Screen.MusicAlbum -> "MusicAlbum"
    is Screen.MusicArtist -> "MusicArtist"
    is Screen.MusicSongs -> "MusicSongs"
    is Screen.MusicPlaylist -> "MusicPlaylist"
    Screen.MusicNowPlaying -> "MusicNowPlaying"
    Screen.MusicQueue -> "MusicQueue"
}

/**
 * Where opening a library, Home or search card should navigate. A channel folder item (docs on
 * [tv.jellybeam.data.CoreGateway.liveChildren]) is another browse level, not a detail page --
 * rendered live via `ChannelFolderList`, never mirror-backed, since channel content is
 * excluded from the offline mirror.
 */
fun screenForLibraryCard(card: Card): Screen = when (card.itemType) {
    CHANNEL_FOLDER_ITEM_TYPE -> Screen.Library(ViewSnapshot(id = card.id, name = card.name, kind = ViewKind.CHANNEL_FOLDER))
    // docs/29 §9: music items open the music pages; a song opens its album, or plays where it has none,
    // and a playlist opens the music page only when the server calls it Audio.
    "MusicAlbum" -> Screen.MusicAlbum(card.id)
    "MusicArtist" -> Screen.MusicArtist(card.id)
    "Audio" -> card.albumId?.let { Screen.MusicAlbum(it) } ?: Screen.Detail(card)
    "Playlist" -> if (card.mediaType == "Audio") Screen.MusicPlaylist(card) else Screen.Detail(card)
    else -> Screen.Detail(card)
}

/** docs/29 §9 A entry: a library with collection type `music` opens the music landing. */
fun screenForView(view: ViewSnapshot): Screen = if (view.collectionType == "music") Screen.MusicLibrary(view) else Screen.Library(view)
