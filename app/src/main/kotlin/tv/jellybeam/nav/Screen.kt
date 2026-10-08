package tv.jellybeam.nav

import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrBrowseKind
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
}

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
}

/**
 * Where opening a Library grid card should navigate. A channel folder item (docs on
 * [tv.jellybeam.data.CoreGateway.liveChildren]) is another browse level, not a detail page --
 * rendered live via `ChannelFolderList`, never mirror-backed, since channel content is
 * excluded from the offline mirror.
 */
fun screenForLibraryCard(card: Card): Screen =
    if (card.itemType == CHANNEL_FOLDER_ITEM_TYPE) {
        Screen.Library(ViewSnapshot(id = card.id, name = card.name, kind = ViewKind.CHANNEL_FOLDER))
    } else {
        Screen.Detail(card)
    }
