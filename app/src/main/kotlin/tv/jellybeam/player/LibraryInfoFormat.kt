package tv.jellybeam.player

import java.util.Locale
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
    fun buildSheet(detail: ItemDetail): LibrarySheetContent {
        val isEpisode = detail.itemType == EPISODE_TYPE
        return LibrarySheetContent(
            metaSegments = metaSegments(detail, isEpisode),
            synopsis = detail.overview?.takeIf(String::isNotBlank),
            fields = sheetFields(detail),
        )
    }

    /**
     * docs/12 §17 meta line: `"S4 E18 · Mar 10, 2011 · 22m · TV-PG · 7.3/10 · Comedy, Romance"`
     * (bare production year for non-episodes). The rating pair is two adjacent spans with no
     * separator since together they're one logical segment.
     */
    private fun metaSegments(detail: ItemDetail, isEpisode: Boolean): List<StatsSpan> {
        val segments = buildList<List<StatsSpan>> {
            if (isEpisode) {
                CardFormatting.seasonEpisodeLabel(detail.parentIndexNumber, detail.indexNumber)?.let {
                    add(listOf(StatsSpan(it)))
                }
            } else {
                detail.productionYear?.let { add(listOf(StatsSpan(it.toString()))) }
            }
            DetailFormatting.shortDate(detail.premiereDate)?.let { add(listOf(StatsSpan(it))) }
            detail.runTimeTicks?.let { add(listOf(StatsSpan(CardFormatting.formatRuntime(it)))) }
            detail.officialRating?.takeIf(String::isNotBlank)?.let { add(listOf(StatsSpan(it))) }
            detail.communityRating?.takeIf { it.isFinite() && it >= 0f }?.let {
                add(listOf(StatsSpan(String.format(Locale.US, "%.1f", it), accent = true), StatsSpan("/10")))
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
    private fun sheetFields(detail: ItemDetail): List<LibraryInfoFact> = buildList {
        DetailFormatting.peopleLine(detail.directors)?.let { add(LibraryInfoFact("Director", it)) }
        writersValue(detail.writers)?.let { add(LibraryInfoFact("Writers", it)) }
        add(LibraryInfoFact("Watched", watchedValue(detail.playCount, detail.lastPlayedDate)))
        DetailFormatting.shortDate(detail.dateCreated)?.let { add(LibraryInfoFact("Added", it)) }
    }

    /** `"A, B"` for one/two writers, `"A, B + N"` for more (first two + N); `null` for none. */
    private fun writersValue(writers: List<String>): String? {
        val clean = writers.filter(String::isNotBlank)
        if (clean.isEmpty()) return null
        val firstTwo = clean.take(2).joinToString(", ")
        val extra = clean.size - 2
        return if (extra > 0) "$firstTwo + $extra" else firstTwo
    }

    /** Watched fact: `"N times · last {date}"` (`"1 time"` for one) or `"Never"`; never dropped. */
    private fun watchedValue(playCount: Int, lastPlayedDate: String?): String {
        if (playCount <= 0) return "Never"
        val timesLabel = if (playCount == 1) "1 time" else "$playCount times"
        val lastDate = DetailFormatting.shortDate(lastPlayedDate)
        return if (lastDate != null) "$timesLabel · last $lastDate" else timesLabel
    }
}
