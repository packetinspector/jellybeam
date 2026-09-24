package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBreadcrumbTest {

    @Test
    fun `a Movie renders the bare title regardless of the other fields`() {
        assertEquals(
            "A Synthetic Movie",
            PlaybackBreadcrumb.format(
                itemType = "Movie",
                itemName = "A Synthetic Movie",
                seriesName = "should be ignored",
                parentIndexNumber = 9,
                indexNumber = 9,
            ),
        )
    }

    @Test
    fun `an Episode with full metadata renders series, season-episode, and title`() {
        assertEquals(
            "Series Alpha · S2 E4 · Episode Four",
            PlaybackBreadcrumb.format(
                itemType = "Episode",
                itemName = "Episode Four",
                seriesName = "Series Alpha",
                parentIndexNumber = 2,
                indexNumber = 4,
            ),
        )
    }

    @Test
    fun `an Episode with no series or index metadata degrades to the bare title`() {
        assertEquals(
            "Episode Four",
            PlaybackBreadcrumb.format(
                itemType = "Episode",
                itemName = "Episode Four",
                seriesName = null,
                parentIndexNumber = null,
                indexNumber = null,
            ),
        )
    }

    @Test
    fun `an Episode with a series name but no season-episode numbers never shows a stray separator`() {
        assertEquals(
            "Series Alpha · Episode Four",
            PlaybackBreadcrumb.format(
                itemType = "Episode",
                itemName = "Episode Four",
                seriesName = "Series Alpha",
                parentIndexNumber = null,
                indexNumber = null,
            ),
        )
    }

    @Test
    fun `an Episode with only an index number omits the series segment`() {
        assertEquals(
            "E4 · Episode Four",
            PlaybackBreadcrumb.format(
                itemType = "Episode",
                itemName = "Episode Four",
                seriesName = null,
                parentIndexNumber = null,
                indexNumber = 4,
            ),
        )
    }
}
