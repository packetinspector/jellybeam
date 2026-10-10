package tv.jellybeam.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/11 §Loading state: the pure decisions behind every skeleton -> content -> gone region. */
class DetailRegionsTest {

    @Test
    fun `regionShow table -- content wins, then unsettled is a skeleton, else gone`() {
        // settled, hasContent -> expected
        val table = listOf(
            Triple(false, false, RegionShow.SKELETON),
            // The mirror record's overview/genres arrive before the live record settles.
            Triple(false, true, RegionShow.CONTENT),
            Triple(true, true, RegionShow.CONTENT),
            Triple(true, false, RegionShow.GONE),
        )
        for ((settled, hasContent, expected) in table) {
            assertEquals("settled=$settled hasContent=$hasContent", expected, regionShow(settled, hasContent))
        }
    }

    @Test
    fun `movieEyebrowShow settles only when both the library name and the detail record have`() {
        assertEquals(RegionShow.SKELETON, movieEyebrowShow(libraryNameLoaded = false, itemDetailLoaded = false, hasEyebrow = false))
        assertEquals(RegionShow.SKELETON, movieEyebrowShow(libraryNameLoaded = true, itemDetailLoaded = false, hasEyebrow = false))
        assertEquals(RegionShow.SKELETON, movieEyebrowShow(libraryNameLoaded = false, itemDetailLoaded = true, hasEyebrow = false))
        assertEquals(RegionShow.GONE, movieEyebrowShow(libraryNameLoaded = true, itemDetailLoaded = true, hasEyebrow = false))
        assertEquals(RegionShow.CONTENT, movieEyebrowShow(libraryNameLoaded = false, itemDetailLoaded = false, hasEyebrow = true))
    }

    @Test
    fun `lowerBandPresent is true while either cell is loading or has content`() {
        val all = RegionShow.values()
        for (spec in all) {
            for (upNext in all) {
                val expected = !(spec == RegionShow.GONE && upNext == RegionShow.GONE)
                assertEquals("spec=$spec upNext=$upNext", expected, lowerBandPresent(spec, upNext))
            }
        }
    }

    @Test
    fun `lowerBandPresent holds from the first frame -- both cells loading`() {
        assertTrue(lowerBandPresent(RegionShow.SKELETON, RegionShow.SKELETON))
    }

    @Test
    fun `lowerBandPresent collapses only when both cells settled empty`() {
        assertFalse(lowerBandPresent(RegionShow.GONE, RegionShow.GONE))
        assertTrue(lowerBandPresent(RegionShow.GONE, RegionShow.CONTENT))
        assertTrue(lowerBandPresent(RegionShow.CONTENT, RegionShow.GONE))
    }

    @Test
    fun `seasonChipsShow reserves the row until seasons settle, then needs two or more`() {
        assertEquals(RegionShow.SKELETON, seasonChipsShow(seasonsSettled = false, seasonCount = 0, reportedChildCount = null))
        assertEquals(RegionShow.SKELETON, seasonChipsShow(seasonsSettled = false, seasonCount = 0, reportedChildCount = 3))
        assertEquals(RegionShow.GONE, seasonChipsShow(seasonsSettled = true, seasonCount = 0, reportedChildCount = null))
        assertEquals(RegionShow.GONE, seasonChipsShow(seasonsSettled = true, seasonCount = 1, reportedChildCount = null))
        assertEquals(RegionShow.CONTENT, seasonChipsShow(seasonsSettled = true, seasonCount = 2, reportedChildCount = null))
    }

    @Test
    fun `seasonChipsShow is gone before seasons settle when the record reports one season or none`() {
        assertEquals(RegionShow.GONE, seasonChipsShow(seasonsSettled = false, seasonCount = 0, reportedChildCount = 1))
        assertEquals(RegionShow.GONE, seasonChipsShow(seasonsSettled = false, seasonCount = 0, reportedChildCount = 0))
        assertEquals(RegionShow.GONE, seasonChipsShow(seasonsSettled = true, seasonCount = 1, reportedChildCount = 1))
    }

    @Test
    fun `movieChipsShow waits on the detail record and shows a rating or any genre`() {
        assertEquals(RegionShow.SKELETON, movieChipsShow(itemDetailLoaded = false, hasRating = false, genreCount = 0))
        assertEquals(RegionShow.CONTENT, movieChipsShow(itemDetailLoaded = false, hasRating = true, genreCount = 0))
        assertEquals(RegionShow.CONTENT, movieChipsShow(itemDetailLoaded = true, hasRating = false, genreCount = 2))
        assertEquals(RegionShow.GONE, movieChipsShow(itemDetailLoaded = true, hasRating = false, genreCount = 0))
    }

    // -- episodeShelfShow: the Series shelf's state matrix -------------------------------------

    private fun shelf(
        seasonsSettled: Boolean,
        hasSeasons: Boolean,
        hasSelectedSeason: Boolean,
        isLoadingEpisodes: Boolean,
        hasEpisodes: Boolean,
    ) = episodeShelfShow(seasonsSettled, hasSeasons, hasSelectedSeason, isLoadingEpisodes, hasEpisodes)

    @Test
    fun `episodeShelfShow is a skeleton before seasons settle`() {
        assertEquals(RegionShow.SKELETON, shelf(false, false, false, false, false))
    }

    @Test
    fun `episodeShelfShow keeps its skeleton in the gap between seasons settling and the resume season being selected`() {
        // Seasons landed, the per-episode answer has not: no selection, no loading flag, no episodes.
        assertEquals(RegionShow.SKELETON, shelf(true, true, false, false, false))
    }

    @Test
    fun `episodeShelfShow is a skeleton while the selected season's episodes load`() {
        assertEquals(RegionShow.SKELETON, shelf(true, true, true, true, false))
    }

    @Test
    fun `episodeShelfShow shows content once episodes are there`() {
        assertEquals(RegionShow.CONTENT, shelf(true, true, true, false, true))
    }

    @Test
    fun `episodeShelfShow is gone for a series that settled with no seasons`() {
        assertEquals(RegionShow.GONE, shelf(true, false, false, false, false))
    }

    @Test
    fun `episodeShelfShow is gone for a selected season that settled with no episodes`() {
        assertEquals(RegionShow.GONE, shelf(true, true, true, false, false))
    }

    @Test
    fun `episodeShelfShow is gone once seasons settled empty, even with stale episodes`() {
        assertEquals(RegionShow.GONE, shelf(true, false, true, false, true))
        assertEquals(RegionShow.CONTENT, shelf(false, false, true, false, true))
    }

    @Test
    fun `episodeShelfShow shows content whenever episodes exist, even with seasons unsettled`() {
        assertEquals(RegionShow.CONTENT, shelf(false, false, false, false, true))
        assertEquals(RegionShow.CONTENT, shelf(true, true, false, false, true))
        assertEquals(RegionShow.CONTENT, shelf(true, true, true, true, true))
    }

    @Test
    fun `episodeShelfShow is a skeleton while unsettled even if a season is selected or loading`() {
        assertEquals(RegionShow.SKELETON, shelf(false, true, true, true, false))
        assertEquals(RegionShow.SKELETON, shelf(false, true, true, false, false))
    }

    @Test
    fun `episodeShelfShow with no seasons settles gone even if a stale loading flag is set`() {
        assertEquals(RegionShow.GONE, shelf(true, false, false, true, false))
    }
}
