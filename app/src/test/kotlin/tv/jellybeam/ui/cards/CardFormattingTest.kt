package tv.jellybeam.ui.cards

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.ImageKind

class CardFormattingTest {

    @Test
    fun `backdrop art source prefers the item's own backdrop tag`() {
        val card = testCard(itemType = "Movie", backdropTag = "bd-tag", primaryTag = "poster-tag")
        assertEquals(ArtSource.Own("item-1", "bd-tag", ImageKind.BACKDROP), CardFormatting.backdropArtSource(card))
    }

    @Test
    fun `backdrop art source falls back to the parent backdrop`() {
        val card = testCard(
            itemType = "Episode",
            backdropTag = null,
            parentBackdropItemId = "series-1",
            parentBackdropTag = "parent-bd",
        )
        assertEquals(ArtSource.Fallback("series-1", "parent-bd", ImageKind.BACKDROP), CardFormatting.backdropArtSource(card))
    }

    @Test
    fun `backdrop art source uses an episode's landscape primary as a last resort`() {
        val card = testCard(itemType = "Episode", primaryTag = "still-tag")
        assertEquals(ArtSource.Own("item-1", "still-tag", ImageKind.PRIMARY), CardFormatting.backdropArtSource(card))
    }

    @Test
    fun `backdrop art source never stretches a non-episode poster`() {
        val card = testCard(itemType = "Movie", primaryTag = "poster-tag")
        assertEquals(ArtSource.None, CardFormatting.backdropArtSource(card))
    }

    @Test
    fun `format runtime shows hours and minutes`() {
        assertEquals("6m", CardFormatting.formatRuntime(3_600_000_000L))
        assertEquals("45m", CardFormatting.formatRuntime(45L * 60 * 10_000_000))
        assertEquals("1h 30m", CardFormatting.formatRuntime(90L * 60 * 10_000_000))
        // Sub-minute rounds up: "0m" reads as broken metadata.
        assertEquals("1m", CardFormatting.formatRuntime(5L * 10_000_000))
        // Partial minutes ceil too: 6m01s -> "7m".
        assertEquals("7m", CardFormatting.formatRuntime((6L * 60 + 1) * 10_000_000))
        assertEquals("0m", CardFormatting.formatRuntime(0L))
    }

    @Test
    fun `episode title is E-n dot name`() {
        assertEquals("E9 · Pilot", CardFormatting.episodeTitle("Pilot", 9))
    }

    @Test
    fun `episode title with no index number is a bare name`() {
        assertEquals("Pilot", CardFormatting.episodeTitle("Pilot", null))
    }

    @Test
    fun `season episode label formats both numbers`() {
        assertEquals("S3 E9", CardFormatting.seasonEpisodeLabel(3, 9))
    }

    @Test
    fun `season episode label drops a missing season number`() {
        assertEquals("E9", CardFormatting.seasonEpisodeLabel(null, 9))
    }

    @Test
    fun `season episode label drops a missing episode number`() {
        assertEquals("S3", CardFormatting.seasonEpisodeLabel(3, null))
    }

    @Test
    fun `season episode label is null with neither number`() {
        assertNull(CardFormatting.seasonEpisodeLabel(null, null))
    }

    @Test
    fun `remaining label subtracts position from runtime`() {
        val runtime = 30L * 60 * 10_000_000
        val position = 18L * 60 * 10_000_000
        assertEquals("12m left", CardFormatting.remainingLabel(runtime, position))
    }

    @Test
    fun `remaining label drops left when unstarted`() {
        // Unstarted (positionTicks == 0) shows plain runtime, never a "left" suffix.
        val runtime = 42L * 60 * 10_000_000
        assertEquals("42m", CardFormatting.remainingLabel(runtime, 0))
    }

    @Test
    fun `remaining label is null with no runtime`() {
        assertNull(CardFormatting.remainingLabel(null, 0))
    }

    @Test
    fun `virtual status label is airs date for a future premiere`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val future = "2026-01-31T00:00:00Z"
        assertEquals("Airs Jan 31", CardFormatting.virtualStatusLabel(future, now))
    }

    @Test
    fun `virtual status label is missing for a past premiere`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val past = "2025-01-01T00:00:00Z"
        assertEquals("Missing", CardFormatting.virtualStatusLabel(past, now))
    }

    @Test
    fun `virtual status label is missing with no premiere date at all`() {
        assertEquals("Missing", CardFormatting.virtualStatusLabel(null))
    }

    @Test
    fun `virtual status label is missing for an unparseable date`() {
        assertEquals("Missing", CardFormatting.virtualStatusLabel("not-a-date"))
    }

    @Test
    fun `resume meta line joins season episode and remaining`() {
        val card = testCard(
            itemType = "Episode",
            parentIndexNumber = 3,
            indexNumber = 9,
            runtimeTicks = 30L * 60 * 10_000_000,
            positionTicks = 18L * 60 * 10_000_000,
        )
        assertEquals("S3 E9 · 12m left", CardFormatting.resumeMetaLine(card))
    }

    @Test
    fun `resume meta line is just remaining time for a movie`() {
        val card = testCard(
            itemType = "Movie",
            runtimeTicks = 120L * 60 * 10_000_000,
            positionTicks = 90L * 60 * 10_000_000,
        )
        assertEquals("30m left", CardFormatting.resumeMetaLine(card))
    }

    @Test
    fun `resume meta line uses virtual status label for a virtual episode`() {
        val card = testCard(
            itemType = "Episode",
            parentIndexNumber = 2,
            indexNumber = 4,
            isVirtual = true,
            premiereDate = null,
        )
        assertEquals("S2 E4 · Missing", CardFormatting.resumeMetaLine(card))
    }

    @Test
    fun `resume meta line is null with nothing to show`() {
        val card = testCard(itemType = "Movie")
        assertNull(CardFormatting.resumeMetaLine(card))
    }

    @Test
    fun `resume meta line drops left for an unstarted episode`() {
        // Next Up cards are unstarted (positionTicks == 0): meta line reads as plain runtime, not
        // "X left".
        val card = testCard(
            itemType = "Episode",
            parentIndexNumber = 3,
            indexNumber = 9,
            runtimeTicks = 30L * 60 * 10_000_000,
            positionTicks = 0,
        )
        assertEquals("S3 E9 · 30m", CardFormatting.resumeMetaLine(card))
    }

    @Test
    fun `resume timing label is the remaining time`() {
        val card = testCard(
            itemType = "Episode",
            runtimeTicks = 30L * 60 * 10_000_000,
            positionTicks = 18L * 60 * 10_000_000,
        )
        assertEquals("12m left", CardFormatting.resumeTimingLabel(card))
    }

    @Test
    fun `resume timing label uses virtual status label for a virtual episode`() {
        val card = testCard(itemType = "Episode", isVirtual = true, premiereDate = null)
        assertEquals("Missing", CardFormatting.resumeTimingLabel(card))
    }

    @Test
    fun `resume timing label is null with nothing to show`() {
        val card = testCard(itemType = "Movie")
        assertNull(CardFormatting.resumeTimingLabel(card))
    }

    @Test
    fun `resume timing label drops left for an unstarted card`() {
        // Next Up shelf thumb pill: positionTicks == 0 there, so reads "55m", never "55m left".
        val card = testCard(itemType = "Episode", runtimeTicks = 55L * 60 * 10_000_000, positionTicks = 0)
        assertEquals("55m", CardFormatting.resumeTimingLabel(card))
    }

    @Test
    fun `resume series season line joins series name and season`() {
        val card = testCard(itemType = "Episode", seriesName = "Firefly", parentIndexNumber = 7)
        assertEquals("Firefly · S7", CardFormatting.resumeSeriesSeasonLine(card))
    }

    @Test
    fun `resume series season line drops a missing season number`() {
        val card = testCard(itemType = "Episode", seriesName = "Firefly", parentIndexNumber = null)
        assertEquals("Firefly", CardFormatting.resumeSeriesSeasonLine(card))
    }

    @Test
    fun `resume series season line drops a missing series name`() {
        val card = testCard(itemType = "Episode", seriesName = null, parentIndexNumber = 7)
        assertEquals("S7", CardFormatting.resumeSeriesSeasonLine(card))
    }

    @Test
    fun `resume series season line is null for a movie`() {
        val card = testCard(itemType = "Movie", seriesName = "Firefly", parentIndexNumber = 7)
        assertNull(CardFormatting.resumeSeriesSeasonLine(card))
    }

    @Test
    fun `watch progress is null with no runtime`() {
        val card = testCard(runtimeTicks = null, positionTicks = 100)
        assertNull(CardFormatting.watchProgress(card))
    }

    @Test
    fun `watch progress is null when unstarted`() {
        val card = testCard(runtimeTicks = 100, positionTicks = 0)
        assertNull(CardFormatting.watchProgress(card))
    }

    @Test
    fun `watch progress is the clamped fraction`() {
        val card = testCard(runtimeTicks = 100, positionTicks = 50)
        assertEquals(0.5f, CardFormatting.watchProgress(card)!!, 0.0001f)
    }

    @Test
    fun `watch indicator is unplayed count for a series with unplayed items`() {
        val card = testCard(itemType = "Series", unplayedCount = 4)
        assertEquals(WatchIndicator.UnplayedCount(4), CardFormatting.watchIndicator(card, null))
    }

    @Test
    fun `watch indicator is none for a series with zero unplayed`() {
        val card = testCard(itemType = "Series", unplayedCount = 0)
        assertEquals(WatchIndicator.None, CardFormatting.watchIndicator(card, null))
    }

    @Test
    fun `watch indicator is watched check for a played movie not in progress`() {
        val card = testCard(itemType = "Movie", played = true)
        assertEquals(WatchIndicator.WatchedCheck, CardFormatting.watchIndicator(card, null))
    }

    @Test
    fun `watch indicator is none for a resumable item even if played is stale-true`() {
        val card = testCard(itemType = "Movie", played = true)
        assertEquals(WatchIndicator.None, CardFormatting.watchIndicator(card, progress = 0.3f))
    }

    // ---- art fallback chains -----------------------------------------------

    @Test
    fun `poster art source is the item's own tag for a non-episode`() {
        val card = testCard(itemType = "Movie", primaryTag = "tag-1")
        assertEquals(ArtSource.Own("item-1", "tag-1", ImageKind.PRIMARY), CardFormatting.posterArtSource(card))
    }

    @Test
    fun `poster art source never uses an episode's own primary tag`() {
        val card = testCard(
            itemType = "Episode",
            primaryTag = "episode-tag",
            seriesId = "series-1",
            seriesPrimaryTag = "series-tag",
        )
        assertEquals(ArtSource.Fallback("series-1", "series-tag", ImageKind.PRIMARY), CardFormatting.posterArtSource(card))
    }

    @Test
    fun `poster art source is none with nothing to fall back to`() {
        val card = testCard(itemType = "Episode", primaryTag = "episode-tag")
        assertEquals(ArtSource.None, CardFormatting.posterArtSource(card))
    }

    @Test
    fun `rail art source is the item's own tag first`() {
        val card = testCard(itemType = "Episode", primaryTag = "still-tag")
        assertEquals(ArtSource.Own("item-1", "still-tag", ImageKind.PRIMARY), CardFormatting.railArtSource(card))
    }

    @Test
    fun `rail art source falls back to the parent backdrop`() {
        val card = testCard(
            itemType = "Episode",
            primaryTag = null,
            parentBackdropItemId = "series-1",
            parentBackdropTag = "backdrop-tag",
        )
        assertEquals(ArtSource.Fallback("series-1", "backdrop-tag", ImageKind.BACKDROP), CardFormatting.railArtSource(card))
    }

    // ---- resumeArtSource (resume/next-up shelf's uniform 16:9 chain) ------

    // Android TV deviation from docs/07 §1's mixed-aspect row: movies use posters,
    // episodes use a screen cap (owner product requirement).

    @Test
    fun `resume art source uses an episode's rail chain`() {
        val card = testCard(itemType = "Episode", primaryTag = "still-tag")
        assertEquals(CardFormatting.railArtSource(card), CardFormatting.resumeArtSource(card))
    }

    @Test
    fun `resume art source uses a movie's own backdrop`() {
        val card = testCard(itemType = "Movie", backdropTag = "bd-tag", primaryTag = "poster-tag")
        assertEquals(ArtSource.Own("item-1", "bd-tag", ImageKind.BACKDROP), CardFormatting.resumeArtSource(card))
    }

    @Test
    fun `resume art source never falls back to a movie's poster`() {
        // No backdrop anywhere: placeholder tile, never a stretched 2:3 poster in the 16:9 slot.
        val card = testCard(itemType = "Movie", primaryTag = "poster-tag")
        assertEquals(ArtSource.None, CardFormatting.resumeArtSource(card))
    }

    // ---- resume row sizing -------------------------------------------------

    // 6.5 across, clamp [172,280]dp -- owner feedback that images read too big
    // at couch distance. Floor is 172dp per spec §7.
    @Test
    fun `resume card width is clamped to the 172 to 280 range`() {
        assertEquals(172f, CardFormatting.resumeCardWidthDp(200f), 0.01f)
        assertEquals(280f, CardFormatting.resumeCardWidthDp(4000f), 0.01f)
    }

    @Test
    fun `resume row height follows the episode width's 16 by 9 aspect`() {
        val width = CardFormatting.resumeCardWidthDp(1500f)
        val height = CardFormatting.resumeRowHeightDp(1500f)
        assertTrue(kotlin.math.abs(height - width * 9f / 16f) < 0.01f)
    }

    @Test
    fun `detail meta line joins year and runtime`() {
        assertEquals("2024 · 1h 30m", CardFormatting.detailMetaLine(2024, 90L * 60 * 10_000_000))
    }

    @Test
    fun `detail meta line drops a missing runtime`() {
        assertEquals("2024", CardFormatting.detailMetaLine(2024, null))
    }

    @Test
    fun `detail meta line drops a missing year`() {
        assertEquals("45m", CardFormatting.detailMetaLine(null, 45L * 60 * 10_000_000))
    }

    @Test
    fun `detail meta line is null with neither year nor runtime`() {
        assertNull(CardFormatting.detailMetaLine(null, null))
    }

    @Test
    fun `hero meta line joins season episode runtime and year for an episode`() {
        val card = testCard(
            itemType = "Episode",
            parentIndexNumber = 3,
            indexNumber = 9,
            runtimeTicks = 45L * 60 * 10_000_000,
            productionYear = 2024,
        )
        assertEquals("S3 E9 · 45m · 2024", CardFormatting.heroMetaLine(card))
    }

    @Test
    fun `hero meta line drops missing parts for an episode`() {
        val card = testCard(itemType = "Episode", indexNumber = 9, runtimeTicks = null, productionYear = null)
        assertEquals("E9", CardFormatting.heroMetaLine(card))
    }

    @Test
    fun `hero meta line is null for an episode with nothing to show`() {
        val card = testCard(itemType = "Episode")
        assertNull(CardFormatting.heroMetaLine(card))
    }

    @Test
    fun `hero meta line is just the year for a movie`() {
        val card = testCard(itemType = "Movie", productionYear = 2019)
        assertEquals("2019", CardFormatting.heroMetaLine(card))
    }

    @Test
    fun `hero meta line is null for a movie with no year`() {
        val card = testCard(itemType = "Movie", productionYear = null)
        assertNull(CardFormatting.heroMetaLine(card))
    }

    @Test
    fun `hero is resumable once any position exists`() {
        assertTrue(CardFormatting.heroIsResumable(testCard(positionTicks = 1)))
    }

    @Test
    fun `hero is not resumable when unstarted`() {
        assertEquals(false, CardFormatting.heroIsResumable(testCard(positionTicks = 0)))
    }

    @Test
    fun `play button is playable for a non-virtual item`() {
        val card = testCard(isVirtual = false)
        assertEquals(PlayButtonState.Playable, CardFormatting.playButtonState(card))
    }

    @Test
    fun `play button is unavailable with the airs-date label for a future virtual episode`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val card = testCard(isVirtual = true, premiereDate = "2026-01-31T00:00:00Z")
        assertEquals(PlayButtonState.Unavailable("Airs Jan 31"), CardFormatting.playButtonState(card, now))
    }

    @Test
    fun `play button is unavailable with the missing label for a virtual episode with no premiere date`() {
        val card = testCard(isVirtual = true, premiereDate = null)
        assertEquals(PlayButtonState.Unavailable("Missing"), CardFormatting.playButtonState(card))
    }

    @Test
    fun `bucketed image width rounds up to the next bucket`() {
        assertEquals(240u, CardFormatting.bucketedImageWidth(1))
        assertEquals(240u, CardFormatting.bucketedImageWidth(240))
        assertEquals(360u, CardFormatting.bucketedImageWidth(241))
        assertEquals(480u, CardFormatting.bucketedImageWidth(479))
        assertEquals(720u, CardFormatting.bucketedImageWidth(600))
    }

    @Test
    fun `bucketed image width clamps to the largest bucket`() {
        assertEquals(1280u, CardFormatting.bucketedImageWidth(1280))
        assertEquals(1280u, CardFormatting.bucketedImageWidth(4000))
    }

    // docs/07 §1: Home's POSTER shelf (108dp cell), the library grid, and Detail's placeholder
    // key all land on the same 240 bucket at the app's 2.0 density.
    @Test
    fun `poster cell width at 2x density buckets to the shared 240 rendition`() {
        assertEquals(240u, CardFormatting.bucketedImageWidth(216))
    }

    @Test
    fun `hero poster width at 2x density buckets to 360`() {
        assertEquals(360u, CardFormatting.bucketedImageWidth(296))
    }

    @Test
    fun `resume card width buckets across its clamped dp range at 2x density`() {
        val floorPx = (CardFormatting.resumeCardWidthDp(200f) * 2f).toInt()
        val ceilingPx = (CardFormatting.resumeCardWidthDp(4000f) * 2f).toInt()
        assertEquals(360u, CardFormatting.bucketedImageWidth(floorPx))
        assertEquals(720u, CardFormatting.bucketedImageWidth(ceilingPx))
    }

    // ---- art URL resolution (placeholder-cache-key derivation) -------------

    @Test
    fun `resolve art url calls through for an own source`() {
        val source = ArtSource.Own("item-1", "tag-1", ImageKind.PRIMARY)
        val url = CardFormatting.resolveArtUrl(source) { itemId, kind, tag -> "$itemId/$kind/$tag" }
        assertEquals("item-1/PRIMARY/tag-1", url)
    }

    @Test
    fun `resolve art url calls through for a fallback source`() {
        val source = ArtSource.Fallback("series-1", "series-tag", ImageKind.PRIMARY)
        val url = CardFormatting.resolveArtUrl(source) { itemId, kind, tag -> "$itemId/$kind/$tag" }
        assertEquals("series-1/PRIMARY/series-tag", url)
    }

    @Test
    fun `resolve art url is null for no source, never calling imageUrl`() {
        var called = false
        val url = CardFormatting.resolveArtUrl(ArtSource.None) { _, _, _ -> called = true; "unused" }
        assertNull(url)
        assertEquals(false, called)
    }
}
