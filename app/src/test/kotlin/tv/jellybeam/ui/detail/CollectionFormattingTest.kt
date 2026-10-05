package tv.jellybeam.ui.detail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.WatchIndicator
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.ImageKind

private val strings = ResourceUiStrings.default

private const val MINUTE = 600_000_000L

private fun movie(id: String, played: Boolean = false, positionTicks: Long = 0, year: Int? = null, runtimeMinutes: Long? = null) =
    testCard(id = id, itemType = "Movie", name = "$id name", played = played, positionTicks = positionTicks, productionYear = year, runtimeTicks = runtimeMinutes?.let { it * MINUTE })

private fun series(id: String, played: Boolean = false, unplayed: Long? = null) =
    testCard(id = id, itemType = "Series", name = "$id name", played = played, unplayedCount = unplayed)

class CollectionFormattingTest {

    // -- watched / play target ---------------------------------------------

    @Test
    fun `a movie is watched only when played with no saved position`() {
        assertTrue(CollectionFormatting.isWatched(movie("m", played = true)))
        assertFalse(CollectionFormatting.isWatched(movie("m", played = false)))
        assertFalse(CollectionFormatting.isWatched(movie("m", played = false, positionTicks = 5)))
        assertFalse(CollectionFormatting.isWatched(movie("m", played = true, positionTicks = 5)))
    }

    @Test
    fun `a series is watched only when played and nothing is left unplayed`() {
        assertTrue(CollectionFormatting.isWatched(series("s", played = true, unplayed = 0)))
        assertTrue(CollectionFormatting.isWatched(series("s", played = true, unplayed = null)))
        assertFalse(CollectionFormatting.isWatched(series("s", played = true, unplayed = 2)))
        assertFalse(CollectionFormatting.isWatched(series("s", played = false, unplayed = 0)))
    }

    @Test
    fun `the play target is the first member in order that is not fully watched`() {
        val members = listOf(movie("a", played = true), series("b", played = true, unplayed = 3), movie("c"), movie("d"))
        val target = CollectionFormatting.playTarget(members)!!
        assertEquals("b", target.member.id)
        assertFalse(target.replay)
    }

    @Test
    fun `a resume in progress counts as not watched and stays the target`() {
        val members = listOf(movie("a", played = true), movie("b", positionTicks = 99), movie("c"))
        assertEquals("b", CollectionFormatting.playTarget(members)!!.member.id)
    }

    @Test
    fun `a member part-way through beats an earlier unwatched one, as on the series page`() {
        val members = listOf(movie("a", played = true), movie("b"), movie("c", positionTicks = 99))
        val target = CollectionFormatting.playTarget(members)!!
        assertEquals("c", target.member.id)
        assertFalse(target.replay)
    }

    @Test
    fun `members Play can't start are never the target`() {
        val members = listOf(
            testCard(id = "nested", itemType = "BoxSet"),
            testCard(id = "album", itemType = "MusicAlbum"),
            testCard(id = "book", itemType = "Book"),
            movie("film"),
        )
        assertEquals("film", CollectionFormatting.playTarget(members)!!.member.id)
        assertNull(CollectionFormatting.playTarget(members.take(3)))
    }

    @Test
    fun `a series with no episode yields to the next candidate`() = runTest {
        val members = listOf(series("empty", unplayed = 1), movie("film", runtimeMinutes = 90))
        val play = CollectionFormatting.resolvePlay(strings, members, previous = null) { emptyList() }!!
        assertEquals("film", play.targetId)
    }

    @Test
    fun `a failed episode fetch keeps the previous target only when it was that series`() = runTest {
        val members = listOf(series("s", unplayed = 2), movie("film"))
        val sameSeries = CollectionFormatting.CollectionPlay(series("s", unplayed = 2), episode("e", 1, 2), fromStart = false, replayAll = false)
        assertEquals(sameSeries, CollectionFormatting.resolvePlay(strings, members, sameSeries) { null })

        // A stale target elsewhere (finished or removed) never survives; the next candidate wins.
        val elsewhere = CollectionFormatting.CollectionPlay(movie("gone"), null, fromStart = false, replayAll = false)
        assertEquals("film", CollectionFormatting.resolvePlay(strings, members, elsewhere) { null }!!.targetId)
    }

    @Test
    fun `a series target resolves to its episode and only a fully watched collection replays`() = runTest {
        val ep = episode("e", 1, 3)
        val play = CollectionFormatting.resolvePlay(strings, listOf(series("s", unplayed = 1)), previous = null) { listOf(ep) }!!
        assertEquals("e", play.targetId)
        assertFalse(play.replayAll)
        assertNull(CollectionFormatting.resolvePlay(strings, emptyList(), previous = null) { error("no fetch for an empty collection") })
    }

    @Test
    fun `virtual members are never the target`() {
        val members = listOf(testCard(id = "v", itemType = "Movie", isVirtual = true), movie("real"))
        assertEquals("real", CollectionFormatting.playTarget(members)!!.member.id)
    }

    @Test
    fun `everything watched replays member zero`() {
        val members = listOf(movie("a", played = true), movie("b", played = true))
        val target = CollectionFormatting.playTarget(members)!!
        assertEquals("a", target.member.id)
        assertTrue(target.replay)
    }

    @Test
    fun `an empty collection has no play target`() {
        assertNull(CollectionFormatting.playTarget(emptyList()))
    }

    // -- series target resolution ------------------------------------------

    private fun episode(id: String, season: Int?, number: Int?, played: Boolean = false, positionTicks: Long = 0) =
        testCard(id = id, itemType = "Episode", parentIndexNumber = season, indexNumber = number, played = played, positionTicks = positionTicks)

    @Test
    fun `a series target resolves to the first episode with progress else the first unplayed`() {
        val show = series("s", unplayed = 2)
        val episodes = listOf(episode("e1", 1, 1, played = true), episode("e2", 1, 2), episode("e3", 1, 3, positionTicks = 7))
        val pick = CollectionFormatting.seriesPlayEpisode(strings, show, episodes, replay = false)!!
        assertEquals("e3", pick.episode.id)
        assertFalse(pick.fromStart)
        val noProgress = CollectionFormatting.seriesPlayEpisode(strings, show, episodes.filter { it.id != "e3" }, replay = false)!!
        assertEquals("e2", noProgress.episode.id)
    }

    @Test
    fun `a series replay starts the first regular episode from the beginning, skipping Specials`() {
        val episodes = listOf(episode("sp", 0, 1, played = true), episode("e1", 1, 1, played = true))
        val pick = CollectionFormatting.seriesPlayEpisode(strings, series("s"), episodes, replay = true)!!
        assertEquals("e1", pick.episode.id)
        assertTrue(pick.fromStart)
    }

    @Test
    fun `a series with nothing unplayed falls back to a start-over of the first episode`() {
        val episodes = listOf(episode("e1", 1, 1, played = true))
        val pick = CollectionFormatting.seriesPlayEpisode(strings, series("s", unplayed = 1), episodes, replay = false)!!
        assertEquals("e1", pick.episode.id)
        assertTrue(pick.fromStart)
        assertNull(CollectionFormatting.seriesPlayEpisode(strings, series("s"), emptyList(), replay = false))
    }

    // -- play pill text ----------------------------------------------------

    @Test
    fun `the movie subtext is the name and runtime`() {
        val play = CollectionFormatting.CollectionPlay(movie("m", runtimeMinutes = 102), null, fromStart = false, replayAll = false)
        assertEquals("m name · 1h 42m", CollectionFormatting.playSubtext(strings, play))
        assertEquals("Play", CollectionFormatting.playLabel(strings, play))
    }

    @Test
    fun `a movie part-way through reads Resume with the time left, unless started over`() {
        val partWay = movie("m", positionTicks = 49 * MINUTE, runtimeMinutes = 102)
        val resume = CollectionFormatting.CollectionPlay(partWay, null, fromStart = false, replayAll = false)
        assertEquals("Resume", CollectionFormatting.playLabel(strings, resume))
        assertEquals("m name · 53m left", CollectionFormatting.playSubtext(strings, resume))

        val restart = CollectionFormatting.CollectionPlay(partWay, null, fromStart = true, replayAll = false)
        assertEquals("Play", CollectionFormatting.playLabel(strings, restart))
        assertEquals("m name · 1h 42m", CollectionFormatting.playSubtext(strings, restart))
    }

    @Test
    fun `a series episode part-way through reads Resume`() {
        val ep = testCard(id = "e", itemType = "Episode", parentIndexNumber = 2, indexNumber = 6, positionTicks = 5)
        val play = CollectionFormatting.CollectionPlay(series("s"), ep, fromStart = false, replayAll = false)
        assertEquals("Resume", CollectionFormatting.playLabel(strings, play))
        assertEquals("s name · S2 E6", CollectionFormatting.playSubtext(strings, play))
    }

    @Test
    fun `a movie without runtime shows just its name`() {
        val play = CollectionFormatting.CollectionPlay(movie("m"), null, fromStart = false, replayAll = false)
        assertEquals("m name", CollectionFormatting.playSubtext(strings, play))
    }

    @Test
    fun `the series subtext is the series name and season-episode`() {
        val play = CollectionFormatting.CollectionPlay(series("s"), episode("e", 2, 6), fromStart = false, replayAll = false)
        assertEquals("s name · S2 E6", CollectionFormatting.playSubtext(strings, play))
        assertEquals("e", play.targetId)
    }

    @Test
    fun `a series episode missing numbers degrades to the series name alone`() {
        val play = CollectionFormatting.CollectionPlay(series("s"), episode("e", null, 6), fromStart = false, replayAll = false)
        assertEquals("s name", CollectionFormatting.playSubtext(strings, play))
    }

    @Test
    fun `replaying everything relabels the pill Play again`() {
        val play = CollectionFormatting.CollectionPlay(movie("m"), null, fromStart = true, replayAll = true)
        assertEquals("Play again", CollectionFormatting.playLabel(strings, play))
        assertEquals("m", play.targetId)
    }

    // -- meta line ---------------------------------------------------------

    @Test
    fun `the year range is min to max, one year when equal, none when unknown`() {
        assertEquals("2007–2026", CollectionFormatting.yearRange(listOf(movie("a", year = 2026), movie("b", year = 2007), movie("c"))))
        assertEquals("2020", CollectionFormatting.yearRange(listOf(movie("a", year = 2020), movie("b", year = 2020))))
        assertNull(CollectionFormatting.yearRange(listOf(movie("a"))))
    }

    @Test
    fun `the meta line joins items, watched and years in mono caps`() {
        val members = listOf(movie("a", played = true, year = 2007), movie("b", year = 2026))
        assertEquals("2 ITEMS | 1 WATCHED | 2007–2026", CollectionFormatting.metaLine(strings, members))
    }

    @Test
    fun `the meta line drops the years segment when no member has one and singularizes one item`() {
        assertEquals("1 ITEM | 0 WATCHED", CollectionFormatting.metaLine(strings, listOf(movie("a"))))
        assertEquals("0 ITEMS", CollectionFormatting.metaLine(strings, emptyList()))
    }

    // -- member captions ---------------------------------------------------

    @Test
    fun `a series caption names its season count, Specials excluded`() {
        val seasons = listOf(
            testCard(id = "x0", itemType = "Season", name = "Specials", indexNumber = 0),
            testCard(id = "x1", itemType = "Season", name = "Season 1", indexNumber = 1),
            testCard(id = "x2", itemType = "Season", name = "Season 2", indexNumber = 2),
        )
        assertEquals(2, CollectionFormatting.seasonCount(seasons))
        assertEquals("Series · 12 seasons", CollectionFormatting.cardSubline(strings, series("s"), 12))
        assertEquals("Series · 1 season", CollectionFormatting.cardSubline(strings, series("s"), 1))
        assertEquals("Series", CollectionFormatting.cardSubline(strings, series("s"), null))
    }

    @Test
    fun `a movie caption is year and runtime, an episode keeps the shared line`() {
        assertEquals("2025 · 2h 29m", CollectionFormatting.cardSubline(strings, movie("m", year = 2025, runtimeMinutes = 149), null))
        assertEquals("2025", CollectionFormatting.cardSubline(strings, movie("m", year = 2025), null))
        assertNull(CollectionFormatting.cardSubline(strings, episode("e", 1, 1), null))
    }

    // -- backdrop ----------------------------------------------------------

    @Test
    fun `the collection's own backdrop wins`() {
        val collection = testCard(id = "box", itemType = "BoxSet", backdropTag = "bt")
        val source = CollectionFormatting.backdropSource(collection, listOf(testCard(id = "m", backdropTag = "mt")))
        assertEquals(ArtSource.Own("box", "bt", ImageKind.BACKDROP), source)
    }

    @Test
    fun `without one the first member that has a backdrop supplies it`() {
        val collection = testCard(id = "box", itemType = "BoxSet")
        val members = listOf(testCard(id = "none"), testCard(id = "s", itemType = "Series", backdropTag = "st"), testCard(id = "m", backdropTag = "mt"))
        assertEquals(ArtSource.Own("s", "st", ImageKind.BACKDROP), CollectionFormatting.backdropSource(collection, members))
    }

    @Test
    fun `a member with only a primary image does not stand in for a backdrop`() {
        val collection = testCard(id = "box", itemType = "BoxSet")
        val members = listOf(testCard(id = "ep", itemType = "Episode", primaryTag = "p"))
        assertEquals(ArtSource.None, CollectionFormatting.backdropSource(collection, members))
    }

    // -- stacked card ------------------------------------------------------

    @Test
    fun `a collection with its own poster fronts the stack and the first two members sit behind`() {
        val collection = testCard(id = "box", itemType = "BoxSet", primaryTag = "p")
        val members = listOf(movie("a"), movie("b"), movie("c"))
        assertEquals(listOf("box", "a", "b"), CollectionFormatting.stackLayers(collection, members).map { it.id })
    }

    @Test
    fun `without its own poster member zero is the front and is not repeated behind`() {
        val collection = testCard(id = "box", itemType = "BoxSet")
        val members = listOf(movie("a"), movie("b"), movie("c"), movie("d"))
        assertEquals(listOf("a", "b", "c"), CollectionFormatting.stackLayers(collection, members).map { it.id })
    }

    @Test
    fun `a short or unloaded collection builds a short stack`() {
        val bare = testCard(id = "box", itemType = "BoxSet")
        assertEquals(listOf("a"), CollectionFormatting.stackLayers(bare, listOf(movie("a"))).map { it.id })
        assertTrue(CollectionFormatting.stackLayers(bare, emptyList()).isEmpty())
        val withArt = testCard(id = "box", itemType = "BoxSet", primaryTag = "p")
        assertEquals(listOf("box"), CollectionFormatting.stackLayers(withArt, emptyList()).map { it.id })
    }

    @Test
    fun `the stack caption follows the unplayed count`() {
        val unplayed = testCard(id = "box", itemType = "BoxSet", unplayedCount = 8)
        assertEquals("8 UNPLAYED", CollectionFormatting.stackCaption(strings, unplayed, null))

        val done = testCard(id = "box", itemType = "BoxSet", unplayedCount = 0)
        assertEquals("ALL WATCHED", CollectionFormatting.stackCaption(strings, done, null))
    }

    @Test
    fun `the stack badge counts every member, watched or not, once they load`() {
        val box = testCard(id = "box", itemType = "BoxSet", unplayedCount = 9)
        assertEquals(WatchIndicator.None, CollectionFormatting.stackIndicator(null))
        val members = listOf(movie("a", played = true), movie("b"), series("s", played = false, unplayed = 6))
        assertEquals(WatchIndicator.Count(3), CollectionFormatting.stackIndicator(members))
        val watched = listOf(movie("a", played = true), series("s", played = true, unplayed = 0))
        assertEquals(WatchIndicator.Count(2), CollectionFormatting.stackIndicator(watched))
        assertEquals("ALL WATCHED", CollectionFormatting.stackCaption(strings, box, watched))
    }

    @Test
    fun `loaded members count items the way the page does, not the server's episode total`() {
        val box = testCard(id = "box", itemType = "BoxSet", unplayedCount = 9)
        val members = listOf(movie("a", played = true), movie("b"), series("s", played = false, unplayed = 6))
        assertEquals("2 UNPLAYED", CollectionFormatting.stackCaption(strings, box, members))

        val watched = listOf(movie("a", played = true), series("s", played = true, unplayed = 0))
        assertEquals("ALL WATCHED", CollectionFormatting.stackCaption(strings, box, watched))
    }

    @Test
    fun `an unknown count shows nothing and a known-empty collection is never all watched`() {
        val unknown = testCard(id = "box", itemType = "BoxSet")
        assertNull(CollectionFormatting.stackCaption(strings, unknown, null))

        val empty = testCard(id = "box", itemType = "BoxSet", unplayedCount = 0)
        assertNull(CollectionFormatting.stackCaption(strings, empty, emptyList()))
        assertEquals(WatchIndicator.None, CollectionFormatting.stackIndicator(emptyList()))
    }
}
