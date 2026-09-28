package tv.jellybeam.ui.library

import uniffi.jellybeam_core.ViewKind
import uniffi.jellybeam_core.ViewSnapshot

/** True for any view kind browsed live from the server rather than the offline mirror --
 * `CHANNEL`/`CHANNEL_FOLDER` qualify. Written as "anything but LIBRARY" so a future live kind
 * defaults to "live".
 */
val ViewKind.isLive: Boolean get() = this != ViewKind.LIBRARY

/**
 * Whether [ViewSnapshot] shows the sticky sort/filter UI (docs/16-library-sort-filter.md §4:
 * collection
 * types `movies`/`tvshows` only). Every other `ViewKind.LIBRARY` view still pages through
 * [tv.jellybeam.data.CoreGateway.libraryGrid] at defaults -- equal to the plain grid -- it just never
 * renders
 * the summary line, strip, or index rail on top of it.
 */
val ViewSnapshot.supportsSortFilter: Boolean
    get() = kind == ViewKind.LIBRARY && collectionType in setOf("movies", "tvshows", FAVORITES_COLLECTION_TYPE)

/** docs/16 §2.7: the core's `favorites_view` marks the drawer's Favorites page this way. */
private const val FAVORITES_COLLECTION_TYPE = "favorites"

/** Whether this is the drawer's cross-library Favorites page (docs/16 §2.7): its grid spans
 * every library, so any mirror change may touch it, and its strip adds the Type panel.
 */
val ViewSnapshot.isFavorites: Boolean get() = collectionType == FAVORITES_COLLECTION_TYPE

/** Whether this library is a TV Shows library -- docs/16 §4.1's `SHOWS` noun and §4.2's TV-only
 * chips key off this, not [supportsSortFilter] alone (Movies supports sort/filter too, but never
 * those extras).
 */
val ViewSnapshot.isTvLibrary: Boolean get() = collectionType == "tvshows"

/** docs/16 §2.7: a saved Type filter whose type no favorite has any more; it is cleared rather
 * than left showing an empty grid under a chip the Type panel no longer offers.
 */
fun isStaleItemTypeFilter(itemType: String?, presentTypes: List<String>): Boolean =
    itemType != null && itemType !in presentTypes

/** The summary line's count noun for this view (docs/16 §4.1). */
val ViewSnapshot.gridNoun: GridNoun
    get() = when {
        isFavorites -> GridNoun.FAVORITES
        isTvLibrary -> GridNoun.SHOWS
        else -> GridNoun.MOVIES
    }
