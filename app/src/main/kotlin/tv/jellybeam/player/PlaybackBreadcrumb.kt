package tv.jellybeam.player

import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.cards.CardFormatting

/** `Card::item_type`'s own Episode string -- see [PlaybackViewModel]'s `ITEM_TYPE_EPISODE` for the
 * same convention.
 */
private const val ITEM_TYPE_EPISODE = "Episode"

/**
 * Top-region breadcrumb title (docs/12-osd-ux-spec.md "TOP" region, build order item 3):
 * `"SeriesName · S2 E4 · Episode Title"` for an Episode, bare title otherwise. Reuses
 * [CardFormatting.seasonEpisodeLabel] for the `"S2 E4"` segment.
 *
 * [seriesName]/[parentIndexNumber]/[indexNumber] have no source yet ([PlaybackViewModel] passes
 * `null`), so this degrades to bare [itemName]; the `listOfNotNull` join keeps a partial set
 * (e.g. series name known but not episode numbers) from ever rendering a stray `"· ·"`.
 */
object PlaybackBreadcrumb {
    fun format(
        strings: UiStrings,
        itemType: String,
        itemName: String,
        seriesName: String?,
        parentIndexNumber: Int?,
        indexNumber: Int?,
    ): String {
        if (itemType != ITEM_TYPE_EPISODE) return itemName
        val seasonEpisode = CardFormatting.seasonEpisodeLabel(strings, parentIndexNumber, indexNumber)
        return listOfNotNull(seriesName, seasonEpisode, itemName).joinToString(" · ")
    }
}
