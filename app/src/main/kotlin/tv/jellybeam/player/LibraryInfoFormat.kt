package tv.jellybeam.player

import java.util.Locale
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.detail.DetailFormatting
import uniffi.jellybeam_core.ItemDetail

/** One collector-oriented metadata row in the on-demand playback panel. */
data class LibraryInfoFact(val label: String, val value: String)

/**
 * State of the library-info panel (docs/12 §17). Opens in [Loading]; [PlaybackViewModel] fetches
 * detail only once the viewer opens the sheet, adding no playback-start work.
 */
sealed interface LibraryInfoOverlayState {
    data object Loading : LibraryInfoOverlayState
    data class Content(val value: LibrarySheetContent) : LibraryInfoOverlayState
    data object Unavailable : LibraryInfoOverlayState
}

/**
 * The sheet's content (docs/12 §17). [metaSegments] is a flat [StatsSpan] list already interleaved
 * with literal `" · "` separators, so the renderer never inserts its own.
 */
data class LibrarySheetContent(
    val metaSegments: List<StatsSpan>,
    val synopsis: String?,
    val fields: List<LibraryInfoFact>,
)

object LibraryInfoFormat {
    private const val EPISODE_TYPE = "Episode"

    /** Builds [LibrarySheetContent] (docs/12 §17); each field drops independently when absent. */
    fun buildSheet(strings: UiStrings, detail: ItemDetail, locale: Locale = AppLocale.format): LibrarySheetContent {
        val isEpisode = detail.itemType == EPISODE_TYPE
        return LibrarySheetContent(
            metaSegments = metaSegments(strings, detail, isEpisode, locale),
            synopsis = detail.overview?.takeIf(String::isNotBlank),
            fields = sheetFields(strings, detail),
        )
    }

    /**
     * docs/12 §17 meta line: `"S4 E18 · Mar 10, 2011 · 22m · TV-PG · 7.3/10 · Comedy, Romance"`
     * (bare production year for non-episodes). The rating pair is two adjacent spans with no
     * separator since together they're one logical segment.
     */
    private fun metaSegments(strings: UiStrings, detail: ItemDetail, isEpisode: Boolean, locale: Locale): List<StatsSpan> {
        val segments = buildList<List<StatsSpan>> {
            if (isEpisode) {
                CardFormatting.seasonEpisodeLabel(strings, detail.parentIndexNumber, detail.indexNumber)?.let {
                    add(listOf(StatsSpan(it)))
                }
            } else {
                detail.productionYear?.let { add(listOf(StatsSpan(it.toString()))) }
            }
            DetailFormatting.shortDate(detail.premiereDate)?.let { add(listOf(StatsSpan(it))) }
            detail.runTimeTicks?.let { add(listOf(StatsSpan(CardFormatting.formatRuntime(strings, it)))) }
            detail.officialRating?.takeIf(String::isNotBlank)?.let { add(listOf(StatsSpan(it))) }
            detail.communityRating?.takeIf { it.isFinite() && it >= 0f }?.let {
                add(listOf(StatsSpan(String.format(locale, "%.1f", it), accent = true), StatsSpan(strings.get(R.string.player_library_rating_max))))
            }
            detail.genres.filter(String::isNotBlank).takeIf(List<String>::isNotEmpty)?.joinToString(", ")?.let {
                add(listOf(StatsSpan(it)))
            }
        }
        return buildList {
            segments.forEachIndexed { index, segment ->
                if (index > 0) add(StatsSpan(" · "))
                addAll(segment)
            }
        }
    }

    /** docs/12 §17 fields grid, in order: Director, Writers, Watched, Added. */
    private fun sheetFields(strings: UiStrings, detail: ItemDetail): List<LibraryInfoFact> = buildList {
        DetailFormatting.peopleLine(detail.directors)?.let { add(LibraryInfoFact(strings.get(R.string.player_library_field_director), it)) }
        writersValue(strings, detail.writers)?.let { add(LibraryInfoFact(strings.get(R.string.player_library_field_writers), it)) }
        add(LibraryInfoFact(strings.get(R.string.player_library_field_watched), watchedValue(strings, detail.playCount, detail.lastPlayedDate)))
        DetailFormatting.shortDate(detail.dateCreated)?.let { add(LibraryInfoFact(strings.get(R.string.player_library_field_added), it)) }
    }

    /** `"A, B"` for one/two writers, `"A, B + N"` for more (first two + N); `null` for none. */
    private fun writersValue(strings: UiStrings, writers: List<String>): String? {
        val clean = writers.filter(String::isNotBlank)
        if (clean.isEmpty()) return null
        val firstTwo = clean.take(2).joinToString(", ")
        val extra = clean.size - 2
        return if (extra > 0) strings.get(R.string.player_library_writers_more, firstTwo, extra) else firstTwo
    }

    /** Watched fact: `"N times · last {date}"` (`"1 time"` for one) or `"Never"`; never dropped. */
    private fun watchedValue(strings: UiStrings, playCount: Int, lastPlayedDate: String?): String {
        if (playCount <= 0) return strings.get(R.string.player_library_watched_never)
        val timesLabel = strings.plural(R.plurals.player_library_watched_times, playCount, playCount)
        val lastDate = DetailFormatting.shortDate(lastPlayedDate)
        return if (lastDate != null) strings.get(R.string.player_library_watched_last, timesLabel, lastDate) else timesLabel
    }
}
