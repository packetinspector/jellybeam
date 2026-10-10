package tv.jellybeam.ui.detail

/**
 * What one above-the-fold region of a Movie/Series/Episode page draws (docs/11 §Loading state):
 * a skeleton at its final size, its content, or nothing.
 */
enum class RegionShow { SKELETON, CONTENT, GONE }

/**
 * Content wins even before the source settles (the mirror record's overview and genres arrive
 * ahead of the live one); an unsettled, empty region is a skeleton; a settled, empty one is gone.
 */
fun regionShow(settled: Boolean, hasContent: Boolean): RegionShow = when {
    hasContent -> RegionShow.CONTENT
    !settled -> RegionShow.SKELETON
    else -> RegionShow.GONE
}

/**
 * The Movie eyebrow reads the library name and the mirror record's added date, so it settles only
 * when both have.
 */
fun movieEyebrowShow(libraryNameLoaded: Boolean, itemDetailLoaded: Boolean, hasEyebrow: Boolean): RegionShow =
    regionShow(settled = libraryNameLoaded && itemDetailLoaded, hasContent = hasEyebrow)

/**
 * The Episode page's lower band (spec capsule + Up Next) exists while either cell is still loading
 * or has content, from region states rather than raw data, so Up Next never shifts when the spec
 * strip arrives.
 */
fun lowerBandPresent(spec: RegionShow, upNext: RegionShow): Boolean =
    spec != RegionShow.GONE || upNext != RegionShow.GONE

/**
 * The Series episode shelf. Seasons settling is not enough: the resume season is selected only
 * after the per-episode answer lands (docs/15 §0.2), and until then there are no episodes and no
 * loading flag, so the shelf must keep its skeleton through that gap instead of collapsing and
 * re-opening. A season switch served by the network is the same state ([isLoadingEpisodes]).
 * Settled with no seasons is GONE first: episodes only ever come from a selected season, so any
 * left over are an orphan with no chip.
 */
fun episodeShelfShow(
    seasonsSettled: Boolean,
    hasSeasons: Boolean,
    hasSelectedSeason: Boolean,
    isLoadingEpisodes: Boolean,
    hasEpisodes: Boolean,
): RegionShow = when {
    seasonsSettled && !hasSeasons -> RegionShow.GONE
    hasEpisodes -> RegionShow.CONTENT
    !seasonsSettled -> RegionShow.SKELETON
    !hasSelectedSeason || isLoadingEpisodes -> RegionShow.SKELETON
    else -> RegionShow.GONE
}

/**
 * The Series season chip row exists only for two or more seasons (docs/11 item 12). A record that
 * already reports [reportedChildCount] of one or none skips the skeleton, so a single-season
 * series never shows the row and then collapses it.
 */
fun seasonChipsShow(seasonsSettled: Boolean, seasonCount: Int, reportedChildCount: Int?): RegionShow = when {
    seasonCount <= 1 && reportedChildCount != null && reportedChildCount <= 1 -> RegionShow.GONE
    else -> regionShow(settled = seasonsSettled, hasContent = seasonCount > 1)
}

/** Movie chip group (rating badge + genre chips) settles with the item detail. */
fun movieChipsShow(itemDetailLoaded: Boolean, hasRating: Boolean, genreCount: Int): RegionShow =
    regionShow(settled = itemDetailLoaded, hasContent = hasRating || genreCount > 0)
