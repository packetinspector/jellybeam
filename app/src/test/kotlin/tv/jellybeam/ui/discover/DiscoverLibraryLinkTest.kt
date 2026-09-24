package tv.jellybeam.ui.discover

import tv.jellybeam.MainDispatcherRule
import tv.jellybeam.data.FakeCoreGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import uniffi.jellybeam_core.ItemDetail

/** Same bare-minimum fixture shape as [tv.jellybeam.ui.detail.DetailViewModelTest]'s own
 * `testItemDetail`.
 */
private fun testItemDetail(id: String = "item-1", name: String = "The Prestidigitation Approximation") = ItemDetail(
    id = id,
    name = name,
    itemType = "Movie",
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    premiereDate = "2020-01-01",
    playCount = 2,
    lastPlayedDate = null,
    genres = emptyList(),
    officialRating = null,
    communityRating = null,
    criticRating = null,
    productionYear = 2020,
    endYear = null,
    status = null,
    studios = emptyList(),
    overview = "A synthetic overview.",
    runTimeTicks = 72_000_000_000L,
    container = null,
    people = emptyList(),
    mediaStreams = emptyList(),
    chapters = emptyList(),
    dateCreated = null,
    sizeBytes = null,
    recursiveItemCount = null,
    childCount = null,
    directors = emptyList(),
    writers = emptyList(),
)

class DiscoverLibraryLinkTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `resolves a card from getItemDetail's fields`() = runTest {
        val detail = testItemDetail(id = "movie-1", name = "The Prestidigitation Approximation")
        val fake = FakeCoreGateway(itemDetailResultsByItemId = mapOf("movie-1" to Result.success(detail)))

        val card = resolveLibraryCard(fake, "movie-1")

        assertEquals("movie-1", card?.id)
        assertEquals("The Prestidigitation Approximation", card?.name)
        assertEquals("Movie", card?.itemType)
        assertEquals(2020, card?.productionYear)
        assertEquals(true, card?.played)
        assertEquals(72_000_000_000L, card?.runtimeTicks)
        // Known degradation: ItemDetail carries no image tags at all.
        assertNull(card?.primaryTag)
        assertNull(card?.blurhash)
    }

    @Test
    fun `mirror-first - a cardById hit is returned whole, without touching getItemDetail`() = runTest {
        val mirrorCard = uniffi.jellybeam_core.Card(
            id = "movie-1",
            itemType = "Movie",
            name = "The Prestidigitation Approximation",
            primaryTag = "tag-primary",
            backdropTag = "tag-backdrop",
            thumbTag = null,
            blurhash = "LEHV6nWB2yk8",
            played = false,
            positionTicks = 0L,
            runtimeTicks = 72_000_000_000L,
            unplayedCount = null,
            productionYear = 2020,
            indexNumber = null,
            premiereDate = "2020-01-01",
            parentIndexNumber = null,
            seriesId = null,
            seriesPrimaryTag = null,
            parentBackdropItemId = null,
            parentBackdropTag = null,
            seriesName = null,
            lastPlayedDate = null,
            overview = "A synthetic overview.",
            isVirtual = false,
            libraryId = null,
        )
        val fake = FakeCoreGateway(itemDetailResultsByItemId = mapOf("movie-1" to Result.success(testItemDetail(id = "movie-1"))))
        fake.cardsByItemId["movie-1"] = mirrorCard

        val card = resolveLibraryCard(fake, "movie-1")

        // The full mirror Card comes back verbatim, image tags intact; getItemDetail is never
        // consulted.
        assertEquals(mirrorCard, card)
        assertEquals("tag-primary", card?.primaryTag)
        assertEquals(emptyList<String>(), fake.getItemDetailCalls)
    }

    @Test
    fun `fails open to null when the item can't be resolved`() = runTest {
        val fake = FakeCoreGateway(itemDetailResultsByItemId = mapOf("known-item" to Result.success(testItemDetail())))

        val card = resolveLibraryCard(fake, "unknown-item")

        assertNull(card)
    }

    @Test
    fun `an unplayed item is not marked played`() = runTest {
        val detail = testItemDetail(id = "movie-2").copy(playCount = 0)
        val fake = FakeCoreGateway(itemDetailResultsByItemId = mapOf("movie-2" to Result.success(detail)))

        val card = resolveLibraryCard(fake, "movie-2")

        assertEquals(false, card?.played)
    }
}
