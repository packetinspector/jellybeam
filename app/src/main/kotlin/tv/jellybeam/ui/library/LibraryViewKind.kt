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
    get() = kind == ViewKind.LIBRARY && collectionType in setOf("movies", "tvshows")

/** Whether this library is a TV Shows library -- docs/16 §4.1's `SHOWS` noun and §4.2's TV-only
 * chips key off this, not [supportsSortFilter] alone (Movies supports sort/filter too, but never
 * those extras).
 */
val ViewSnapshot.isTvLibrary: Boolean get() = collectionType == "tvshows"
