package tv.jellybeam.ui.detail

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.ui.cards.testCard
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.CollectionInfo

/** Defaults callers don't care about, so each test's `MenuInput(...)` call shows only what matters.
 */
private fun movieInput(
    card: Card = testCard(id = "movie-1", itemType = "Movie"),
    primaryAction: DetailFormatting.PrimaryAction = DetailFormatting.resolvePrimaryAction(card, emptyList()),
    isFavorite: Boolean = false,
    hasCollections: Boolean = false,
    isAdministrator: Boolean = false,
): MenuInput = MenuInput(
    card = card,
    itemType = card.itemType,
    scopeSeason = null,
    scopeEpisodes = emptyList(),
    allEpisodes = emptyList(),
    primaryAction = primaryAction,
    isFavorite = isFavorite,
    hasCollections = hasCollections,
    isAdministrator = isAdministrator,
    seriesCard = null,
)

private fun episodeInput(
    card: Card = testCard(id = "ep-1", itemType = "Episode"),
    isFavorite: Boolean = false,
    hasCollections: Boolean = false,
    isAdministrator: Boolean = false,
    seriesCard: Card? = null,
): MenuInput = MenuInput(
    card = card,
    itemType = card.itemType,
    scopeSeason = null,
    scopeEpisodes = emptyList(),
    allEpisodes = emptyList(),
    primaryAction = DetailFormatting.resolvePrimaryAction(card, emptyList()),
    isFavorite = isFavorite,
    hasCollections = hasCollections,
    isAdministrator = isAdministrator,
    seriesCard = seriesCard,
)

private fun seriesInput(
    card: Card = testCard(id = "series-1", itemType = "Series"),
    scopeSeason: Card? = null,
    scopeEpisodes: List<Card> = emptyList(),
    allEpisodes: List<Card> = emptyList(),
    isFavorite: Boolean = false,
    hasCollections: Boolean = false,
    isAdministrator: Boolean = false,
    highlightedSeasonNumber: Int? = null,
): MenuInput = MenuInput(
    card = card,
    itemType = card.itemType,
    scopeSeason = scopeSeason,
    scopeEpisodes = scopeEpisodes,
    allEpisodes = allEpisodes,
    primaryAction = DetailFormatting.resolvePrimaryAction(card, allEpisodes),
    isFavorite = isFavorite,
    hasCollections = hasCollections,
    isAdministrator = isAdministrator,
    seriesCard = null,
    highlightedSeasonNumber = highlightedSeasonNumber,
)

private fun MenuModel.rowsOf(group: MenuGroup): List<MenuRow> = groups.firstOrNull { it.group == group }?.rows.orEmpty()
private fun MenuModel.has(group: MenuGroup): Boolean = groups.any { it.group == group }
private fun MenuModel.firstRow(): MenuAction? = groups.flatMap { it.rows }.firstOrNull()?.action

class DetailMenuModelTest {

    // -- §1.1 This title: Movie/Episode single-item marks -------------------

    @Test
    fun `movie never played shows Mark as watched, not unwatched`() {
        val model = buildMenu(movieInput(card = testCard(itemType = "Movie", played = false)))
        val thisTitle = model.rowsOf(MenuGroup.THIS_TITLE)
        assertTrue(thisTitle.any { it.action == MenuAction.MarkWatched })
        assertFalse(thisTitle.any { it.action == MenuAction.MarkUnwatched })
    }

    @Test
    fun `movie never played -- no position, no played flag -- carries NEVER PLAYED subtext`() {
        val model = buildMenu(movieInput(card = testCard(itemType = "Movie", played = false, positionTicks = 0)))
        val row = model.rowsOf(MenuGroup.THIS_TITLE).first { it.action == MenuAction.MarkWatched }
        assertEquals("NEVER PLAYED", row.subtext)
    }

    @Test
    fun `movie with progress but not yet marked played carries no NEVER PLAYED subtext`() {
        val model = buildMenu(movieInput(card = testCard(itemType = "Movie", played = false, positionTicks = 100L)))
        val row = model.rowsOf(MenuGroup.THIS_TITLE).first { it.action == MenuAction.MarkWatched }
        assertNull(row.subtext)
    }

    @Test
    fun `movie already watched shows Mark as unwatched, not watched`() {
        val model = buildMenu(movieInput(card = testCard(itemType = "Movie", played = true)))
        val thisTitle = model.rowsOf(MenuGroup.THIS_TITLE)
        assertTrue(thisTitle.any { it.action == MenuAction.MarkUnwatched })
        assertFalse(thisTitle.any { it.action == MenuAction.MarkWatched })
    }

    @Test
    fun `favorite row shows Add when not a favorite, Remove when it is`() {
        val notFavorite = buildMenu(movieInput(isFavorite = false)).rowsOf(MenuGroup.THIS_TITLE)
        assertTrue(notFavorite.any { it.action == MenuAction.AddFavorite })
        val favorite = buildMenu(movieInput(isFavorite = true)).rowsOf(MenuGroup.THIS_TITLE)
        assertTrue(favorite.any { it.action == MenuAction.RemoveFavorite })
    }

    // -- §1.2 which row is predicted for focus -------------------------------

    @Test
    fun `movie never played predicts Mark as watched`() {
        val model = buildMenu(movieInput(card = testCard(itemType = "Movie", positionTicks = 0, played = false)))
        assertEquals(MenuAction.MarkWatched, model.focusOn)
    }

    @Test
    fun `movie mid-progress predicts Play from the beginning`() {
        val model = buildMenu(movieInput(card = testCard(itemType = "Movie", positionTicks = 5_000L)))
        val predicted = model.focusOn
        assertTrue(predicted is MenuAction.PlayFromBeginning)
        assertTrue(model.rowsOf(MenuGroup.PLAYBACK).any { it.action == predicted })
    }

    @Test
    fun `movie with progress but no Playback rows falls back to the first row instead of naming an absent one`() {
        // Virtual movie: Unavailable primary action means no Playback group, so
        // focusOn must be null (fall back to row 0), never name a row that wasn't built.
        val card = testCard(itemType = "Movie", isVirtual = true, positionTicks = 5_000L)
        val model = buildMenu(movieInput(card = card, primaryAction = DetailFormatting.PrimaryAction.Unavailable("Coming soon")))
        assertFalse(model.has(MenuGroup.PLAYBACK))
        assertNull(model.focusOn)
        assertEquals(MenuAction.MarkWatched, model.firstRow())
    }

    // -- §1.1 Library: Add to collection / Refresh metadata ------------------

    @Test
    fun `Add to collection is absent when the session collections list is empty`() {
        val model = buildMenu(movieInput(hasCollections = false, isAdministrator = true))
        assertFalse(model.rowsOf(MenuGroup.LIBRARY).any { it.action == MenuAction.AddToCollection })
    }

    @Test
    fun `Add to collection is present when the session collections list is non-empty`() {
        val model = buildMenu(movieInput(hasCollections = true))
        assertTrue(model.rowsOf(MenuGroup.LIBRARY).any { it.action == MenuAction.AddToCollection })
    }

    @Test
    fun `Refresh metadata is absent for a non-admin, present for an admin`() {
        assertFalse(buildMenu(movieInput(isAdministrator = false)).rowsOf(MenuGroup.LIBRARY).any { it.action == MenuAction.RefreshMetadata })
        assertTrue(buildMenu(movieInput(isAdministrator = true)).rowsOf(MenuGroup.LIBRARY).any { it.action == MenuAction.RefreshMetadata })
    }

    @Test
    fun `the Library group is absent entirely when it would have no rows`() {
        val model = buildMenu(movieInput(hasCollections = false, isAdministrator = false))
        assertFalse(model.has(MenuGroup.LIBRARY))
    }

    // -- §1.1 Episode: Go to series -------------------------------------------

    @Test
    fun `Go to series is absent when the episode has no resolvable series`() {
        val model = buildMenu(episodeInput(seriesCard = null))
        assertFalse(model.rowsOf(MenuGroup.LIBRARY).any { it.action is MenuAction.GoToSeries })
    }

    @Test
    fun `Go to series carries the series name verbatim, never re-cased`() {
        val series = testCard(id = "series-9", itemType = "Series", name = "sample series title")
        val model = buildMenu(episodeInput(seriesCard = series))
        val row = model.rowsOf(MenuGroup.LIBRARY).first { it.action is MenuAction.GoToSeries }
        assertEquals("sample series title", row.subtext)
        assertEquals("sample series title", (row.action as MenuAction.GoToSeries).series.name)
    }

    @Test
    fun `episode Play from the beginning is present only once positionTicks is greater than zero`() {
        val fresh = buildMenu(episodeInput(card = testCard(itemType = "Episode", positionTicks = 0)))
        assertFalse(fresh.rowsOf(MenuGroup.PLAYBACK).any { it.action is MenuAction.PlayFromBeginning })
        val resumed = buildMenu(episodeInput(card = testCard(itemType = "Episode", positionTicks = 500L)))
        assertTrue(resumed.rowsOf(MenuGroup.PLAYBACK).any { it.action is MenuAction.PlayFromBeginning })
    }

    // -- §1.1 Series (whole-series scope): mark rows -------------------------

    @Test
    fun `series mark rows count only non-virtual episodes, and both coexist when part-watched`() {
        val allEpisodes = listOf(
            testCard(id = "e1", itemType = "Episode", played = false, isVirtual = false),
            testCard(id = "e2", itemType = "Episode", played = false, isVirtual = true), // excluded from every count
            testCard(id = "e3", itemType = "Episode", played = true, isVirtual = false),
        )
        val model = buildMenu(seriesInput(allEpisodes = allEpisodes))
        val thisTitle = model.rowsOf(MenuGroup.THIS_TITLE)
        val watchedRow = thisTitle.first { it.action is MenuAction.MarkScopeWatched }
        val unwatchedRow = thisTitle.first { it.action is MenuAction.MarkScopeUnwatched }
        assertEquals(1, (watchedRow.action as MenuAction.MarkScopeWatched).count)
        assertEquals("1 EPISODE", watchedRow.subtext)
        assertEquals(1, (unwatchedRow.action as MenuAction.MarkScopeUnwatched).count)
        assertEquals("Mark series as watched", watchedRow.label)
        assertEquals("Mark series as unwatched", unwatchedRow.label)
        assertFalse((watchedRow.action as MenuAction.MarkScopeWatched).isSeason)
    }

    @Test
    fun `a fully watched series shows only the unwatched mark row`() {
        val allEpisodes = listOf(testCard(id = "e1", itemType = "Episode", played = true))
        val thisTitle = buildMenu(seriesInput(allEpisodes = allEpisodes)).rowsOf(MenuGroup.THIS_TITLE)
        assertFalse(thisTitle.any { it.action is MenuAction.MarkScopeWatched })
        assertTrue(thisTitle.any { it.action is MenuAction.MarkScopeUnwatched })
    }

    @Test
    fun `a never-played series shows only the watched mark row`() {
        val allEpisodes = listOf(testCard(id = "e1", itemType = "Episode", played = false))
        val thisTitle = buildMenu(seriesInput(allEpisodes = allEpisodes)).rowsOf(MenuGroup.THIS_TITLE)
        assertTrue(thisTitle.any { it.action is MenuAction.MarkScopeWatched })
        assertFalse(thisTitle.any { it.action is MenuAction.MarkScopeUnwatched })
    }

    @Test
    fun `a series with zero episodes shows neither mark row, but still shows favorites`() {
        val thisTitle = buildMenu(seriesInput(allEpisodes = emptyList())).rowsOf(MenuGroup.THIS_TITLE)
        assertFalse(thisTitle.any { it.action is MenuAction.MarkScopeWatched })
        assertFalse(thisTitle.any { it.action is MenuAction.MarkScopeUnwatched })
        assertTrue(thisTitle.any { it.action == MenuAction.AddFavorite })
    }

    // -- §1.1 Series (season scope): mark rows use the season wording -------

    @Test
    fun `season scope mark rows read season, and count only that season's episodes`() {
        val season = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val scopeEpisodes = listOf(
            testCard(id = "s2e1", itemType = "Episode", parentIndexNumber = 2, indexNumber = 1, played = false),
            testCard(id = "s2e2", itemType = "Episode", parentIndexNumber = 2, indexNumber = 2, played = true),
        )
        // A watched episode outside the scoped season must not leak into the count.
        val allEpisodes = scopeEpisodes + testCard(id = "s1e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true)
        val model = buildMenu(seriesInput(scopeSeason = season, scopeEpisodes = scopeEpisodes, allEpisodes = allEpisodes))
        val thisTitle = model.rowsOf(MenuGroup.THIS_TITLE)
        val watchedRow = thisTitle.first { it.action is MenuAction.MarkScopeWatched }
        val unwatchedRow = thisTitle.first { it.action is MenuAction.MarkScopeUnwatched }
        assertEquals("Mark season as watched", watchedRow.label)
        assertEquals("Mark season as unwatched", unwatchedRow.label)
        assertEquals(1, (watchedRow.action as MenuAction.MarkScopeWatched).count)
        assertEquals(1, (unwatchedRow.action as MenuAction.MarkScopeUnwatched).count)
        assertTrue((watchedRow.action as MenuAction.MarkScopeWatched).isSeason)
    }

    // -- §1.1 Playback: Play next unwatched subtext, both forms --------------

    @Test
    fun `whole-series Play next unwatched subtext is S s E n, even crossing into a different season`() {
        val target = testCard(id = "target", itemType = "Episode", parentIndexNumber = 3, indexNumber = 7, played = false)
        val model = buildMenu(seriesInput(allEpisodes = listOf(target)))
        val row = model.rowsOf(MenuGroup.PLAYBACK).first { it.action is MenuAction.PlayNextUnwatched }
        assertEquals("S3 E7", row.subtext)
    }

    @Test
    fun `season-scope Play next unwatched subtext is E n only`() {
        val season = testCard(id = "season-2", itemType = "Season", indexNumber = 2)
        val target = testCard(id = "target", itemType = "Episode", parentIndexNumber = 2, indexNumber = 5, played = false)
        val model = buildMenu(seriesInput(scopeSeason = season, scopeEpisodes = listOf(target), allEpisodes = listOf(target)))
        val row = model.rowsOf(MenuGroup.PLAYBACK).first { it.action is MenuAction.PlayNextUnwatched }
        assertEquals("E5", row.subtext)
    }

    @Test
    fun `Play next unwatched subtext is absent when the episode number itself is missing`() {
        val target = testCard(id = "target", itemType = "Episode", parentIndexNumber = 3, indexNumber = null, played = false)
        val model = buildMenu(seriesInput(allEpisodes = listOf(target)))
        val row = model.rowsOf(MenuGroup.PLAYBACK).first { it.action is MenuAction.PlayNextUnwatched }
        assertNull(row.subtext)
    }

    @Test
    fun `season scope prefers an in-progress episode over the first unplayed one`() {
        val season = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val unplayedFirst = testCard(id = "e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = false)
        val inProgress = testCard(id = "e2", itemType = "Episode", parentIndexNumber = 1, indexNumber = 2, played = false, positionTicks = 500L)
        val scope = listOf(unplayedFirst, inProgress)
        val model = buildMenu(seriesInput(scopeSeason = season, scopeEpisodes = scope, allEpisodes = scope))
        val row = model.rowsOf(MenuGroup.PLAYBACK).first { it.action is MenuAction.PlayNextUnwatched }
        assertEquals("e2", (row.action as MenuAction.PlayNextUnwatched).targetId)
    }

    // -- §1.1 Playback: Play from the beginning / Play something random -----

    @Test
    fun `series Play from the beginning is present only when the primary action is a resume`() {
        val playable = testCard(id = "e1", itemType = "Episode", played = false)
        val notResuming = buildMenu(seriesInput(allEpisodes = listOf(playable))).rowsOf(MenuGroup.PLAYBACK)
        assertFalse(notResuming.any { it.action is MenuAction.PlayFromBeginning })

        val resuming = testCard(id = "e1", itemType = "Episode", positionTicks = 100L)
        val model = buildMenu(seriesInput(allEpisodes = listOf(resuming)))
        assertTrue(model.rowsOf(MenuGroup.PLAYBACK).any { it.action is MenuAction.PlayFromBeginning })
    }

    @Test
    fun `season scope never shows Play from the beginning`() {
        val season = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val resuming = testCard(id = "e1", itemType = "Episode", parentIndexNumber = 1, positionTicks = 100L)
        val model = buildMenu(seriesInput(scopeSeason = season, scopeEpisodes = listOf(resuming), allEpisodes = listOf(resuming)))
        assertFalse(model.rowsOf(MenuGroup.PLAYBACK).any { it.action is MenuAction.PlayFromBeginning })
    }

    @Test
    fun `Play something random counts every non-virtual episode in scope, watched or not`() {
        val allEpisodes = listOf(
            testCard(id = "e1", itemType = "Episode", played = true),
            testCard(id = "e2", itemType = "Episode", played = false),
            testCard(id = "e3", itemType = "Episode", played = false, isVirtual = true),
        )
        val model = buildMenu(seriesInput(allEpisodes = allEpisodes))
        val row = model.rowsOf(MenuGroup.PLAYBACK).first { it.action is MenuAction.PlayRandom }
        assertEquals(2, (row.action as MenuAction.PlayRandom).count)
        assertEquals("FROM 2", row.subtext)
    }

    @Test
    fun `Play something random is absent when the scope has no non-virtual episodes`() {
        val model = buildMenu(seriesInput(allEpisodes = listOf(testCard(id = "e1", itemType = "Episode", isVirtual = true))))
        assertFalse(model.rowsOf(MenuGroup.PLAYBACK).any { it.action is MenuAction.PlayRandom })
    }

    // -- §1.1 Library: season scope drops Add to collection ------------------

    @Test
    fun `season scope never shows Add to collection, even with collections available`() {
        val season = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val model = buildMenu(seriesInput(scopeSeason = season, hasCollections = true, isAdministrator = true))
        val library = model.rowsOf(MenuGroup.LIBRARY)
        assertFalse(library.any { it.action == MenuAction.AddToCollection })
        assertTrue(library.any { it.action == MenuAction.RefreshMetadata })
    }

    @Test
    fun `season scope marks favorites and refresh metadata SERIES -- both still act on the whole series`() {
        val season = testCard(id = "season-1", itemType = "Season", indexNumber = 1)
        val model = buildMenu(seriesInput(scopeSeason = season, isFavorite = true, isAdministrator = true))
        val favoriteRow = model.rowsOf(MenuGroup.THIS_TITLE).first { it.action == MenuAction.RemoveFavorite }
        val refreshRow = model.rowsOf(MenuGroup.LIBRARY).first { it.action == MenuAction.RefreshMetadata }
        assertEquals("SERIES", favoriteRow.subtext)
        assertEquals("SERIES", refreshRow.subtext)
    }

    @Test
    fun `whole-series scope never carries the SERIES subtext on favorites or refresh metadata`() {
        val model = buildMenu(seriesInput(isFavorite = true, isAdministrator = true))
        val favoriteRow = model.rowsOf(MenuGroup.THIS_TITLE).first { it.action == MenuAction.RemoveFavorite }
        val refreshRow = model.rowsOf(MenuGroup.LIBRARY).first { it.action == MenuAction.RefreshMetadata }
        assertNull(favoriteRow.subtext)
        assertNull(refreshRow.subtext)
    }

    // -- §1.2 series/season focusOn: "played OR positionTicks" --------------

    @Test
    fun `fully watched series has no Play next unwatched row, so focus falls back to the first row`() {
        // Fully watched: no resume or next-unwatched target, but Play-random still
        // counts the episode so Playback exists; docs/19 §1.2's predicted row is
        // absent, so focusOn is null (row 0).
        val allEpisodes = listOf(testCard(id = "e1", itemType = "Episode", played = true, positionTicks = 0))
        val model = buildMenu(seriesInput(allEpisodes = allEpisodes))
        assertTrue(model.has(MenuGroup.PLAYBACK))
        assertNull(model.focusOn)
    }

    @Test
    fun `series with no progress anywhere predicts Mark series as watched even though Playback exists`() {
        // Playback group exists (an unplayed episode), but nothing has progress,
        // so focusOn must not guess a Playback row.
        val allEpisodes = listOf(testCard(id = "e1", itemType = "Episode", played = false, positionTicks = 0))
        val model = buildMenu(seriesInput(allEpisodes = allEpisodes))
        assertTrue(model.has(MenuGroup.PLAYBACK))
        assertTrue(model.focusOn is MenuAction.MarkScopeWatched)
    }

    @Test
    fun `episode predicts Play from the beginning when it exists, else falls back to the first row`() {
        val resuming = buildMenu(episodeInput(card = testCard(itemType = "Episode", positionTicks = 100L)))
        assertTrue(resuming.focusOn is MenuAction.PlayFromBeginning)

        val fresh = buildMenu(episodeInput(card = testCard(itemType = "Episode", positionTicks = 0, played = false)))
        assertNull(fresh.focusOn)
        assertEquals(MenuAction.MarkWatched, fresh.firstRow())
    }

    // -- §1.3 Add to collection: alreadyIn -----------------------------------

    @Test
    fun `buildCollectionRows marks alreadyIn only for a collection id present in memberOfCollections`() {
        val collections = listOf(CollectionInfo(id = "c1", name = "Christmas"), CollectionInfo(id = "c2", name = "Date Night"))
        val rows = buildCollectionRows(collections, memberOfCollections = setOf("c2"))
        assertFalse(rows.first { it.collection.id == "c1" }.alreadyIn)
        assertTrue(rows.first { it.collection.id == "c2" }.alreadyIn)
    }

    @Test
    fun `buildCollectionRows preserves the server's own order and names verbatim`() {
        val collections = listOf(CollectionInfo(id = "c1", name = "sample collection name"))
        val rows = buildCollectionRows(collections, memberOfCollections = emptySet())
        assertEquals("sample collection name", rows.single().collection.name)
    }

    @Test
    fun `pickRandom never returns a virtual episode`() {
        val episodes = listOf(
            testCard(id = "virtual", itemType = "Episode", isVirtual = true),
            testCard(id = "real", itemType = "Episode", isVirtual = false),
        )
        repeat(20) {
            val picked = pickRandom(episodes, Random(it))
            assertEquals("real", picked?.id)
        }
    }

    @Test
    fun `pickRandom returns null when there is nothing pickable`() {
        assertNull(pickRandom(emptyList(), Random(0)))
        assertNull(pickRandom(listOf(testCard(id = "only-virtual", itemType = "Episode", isVirtual = true)), Random(0)))
    }
    @Test
    fun `play next unwatched reads E-only when the target sits in the highlighted season, even without an explicit scope`() {
        val allEpisodes = listOf(
            testCard(id = "e1", itemType = "Episode", played = true, parentIndexNumber = 1, indexNumber = 1),
            testCard(id = "e2", itemType = "Episode", played = false, parentIndexNumber = 1, indexNumber = 2),
        )
        val highlighted = buildMenu(seriesInput(allEpisodes = allEpisodes, highlightedSeasonNumber = 1))
        assertEquals("E2", highlighted.rowsOf(MenuGroup.PLAYBACK).first { it.action is MenuAction.PlayNextUnwatched }.subtext)
        val other = buildMenu(seriesInput(allEpisodes = allEpisodes, highlightedSeasonNumber = 3))
        assertEquals("S1 E2", other.rowsOf(MenuGroup.PLAYBACK).first { it.action is MenuAction.PlayNextUnwatched }.subtext)
    }
}
