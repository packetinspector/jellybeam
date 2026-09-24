package tv.jellybeam.ui.discover

import tv.jellybeam.data.CoreGateway
import uniffi.jellybeam_core.Card

/**
 * docs/14-seerr-discover.md's "Go to library" action: resolves `SeerrCard.jellyfinItemId` to a full
 * [Card]
 * via the mirror-only [CoreGateway.cardById], falling back to a best-effort [Card] rebuilt from
 * [CoreGateway.getItemDetail] (no image tags, so it renders over
 * [tv.jellybeam.ui.cards.PlaceholderTile] art)
 * for an id the mirror hasn't synced yet. Fails open (`null`) rather than throwing -- the caller
 * (`MainActivity.openDiscoverLibraryItem`) must never navigate to a broken Detail page.
 */
suspend fun resolveLibraryCard(gateway: CoreGateway, itemId: String): Card? {
    // Mirror-first (normal case); synthetic rebuild below is the fallback.
    runCatching { gateway.cardById(itemId) }.getOrNull()?.let { return it }
    val detail = runCatching { gateway.getItemDetail(itemId) }.getOrNull() ?: return null
    return Card(
        id = detail.id,
        itemType = detail.itemType,
        name = detail.name,
        primaryTag = null,
        backdropTag = null,
        thumbTag = null,
        blurhash = null,
        played = detail.playCount > 0,
        positionTicks = 0L,
        runtimeTicks = detail.runTimeTicks,
        unplayedCount = null,
        productionYear = detail.productionYear,
        indexNumber = detail.indexNumber,
        premiereDate = detail.premiereDate,
        parentIndexNumber = detail.parentIndexNumber,
        seriesId = null,
        seriesPrimaryTag = null,
        parentBackdropItemId = null,
        parentBackdropTag = null,
        seriesName = detail.seriesName,
        lastPlayedDate = detail.lastPlayedDate,
        overview = detail.overview,
        isVirtual = false,
        libraryId = null,
    )
}
