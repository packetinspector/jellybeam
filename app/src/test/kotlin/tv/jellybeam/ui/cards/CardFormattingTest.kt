package tv.jellybeam.ui.cards

import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.i18n.ResourceUiStrings
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ResumeArt

class CardFormattingTest {
    private val strings = ResourceUiStrings.default


    // ---- posterLines / posterEpisodeTag (docs/07 §2) -------------------

    @Test
    fun `a poster episode names itself and its series, and carries the S E tag`() {
        val episode = testCard(itemType = "Episode", name = "Pilot", indexNumber = 5, parentIndexNumber = 1, seriesName = "Harbor Lights")

        assertEquals("E5 · Pilot" to "Harbor Lights · S1", CardFormatting.posterLines(strings, episode))
        assertEquals("S1 E5", CardFormatting.posterEpisodeTag(strings, episode))
    }

    @Test
    fun `a poster season shows its server name over the series name, with no tag`() {
        val season = testCard(itemType = "Season", name = "Season 2", seriesName = "Harbor Lights", productionYear = 2020)

        assertEquals("Season 2" to "Harbor Lights", CardFormatting.posterLines(strings, season))
        assertNull(CardFormatting.posterEpisodeTag(strings, season))
    }

    @Test
    fun `movies, series and collections keep title over year`() {
        for (type in listOf("Movie", "Series", "BoxSet")) {
            val card = testCard(itemType = type, name = "Harbor Lights", productionYear = 1999)
            assertEquals("Harbor Lights" to "1999", CardFormatting.posterLines(strings, card))
            assertNull(CardFormatting.posterEpisodeTag(strings, card))
        }
    }

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
        assertEquals("6m", CardFormatting.formatRuntime(strings, 3_600_000_000L))
        assertEquals("45m", CardFormatting.formatRuntime(strings, 45L * 60 * 10_000_000))
        assertEquals("1h 30m", CardFormatting.formatRuntime(strings, 90L * 60 * 10_000_000))
        // Sub-minute rounds up: "0m" reads as broken metadata.
        assertEquals("1m", CardFormatting.formatRuntime(strings, 5L * 10_000_000))
        // Partial minutes ceil too: 6m01s -> "7m".
        assertEquals("7m", CardFormatting.formatRuntime(strings, (6L * 60 + 1) * 10_000_000))
        assertEquals("0m", CardFormatting.formatRuntime(strings, 0L))
    }

    @Test
    fun `episode title is E-n dot name`() {
        assertEquals("E9 · Pilot", CardFormatting.episodeTitle(strings, "Pilot", 9))
    }

    @Test
    fun `episode title with no index number is a bare name`() {
        assertEquals("Pilot", CardFormatting.episodeTitle(strings, "Pilot", null))
    }

    @Test
    fun `season episode label formats both numbers`() {
        assertEquals("S3 E9", CardFormatting.seasonEpisodeLabel(strings, 3, 9))
    }

    @Test
    fun `season episode label drops a missing season number`() {
        assertEquals("E9", CardFormatting.seasonEpisodeLabel(strings, null, 9))
    }

    @Test
    fun `season episode label drops a missing episode number`() {
        assertEquals("S3", CardFormatting.seasonEpisodeLabel(strings, 3, null))
    }

    @Test
    fun `season episode label is null with neither number`() {
        assertNull(CardFormatting.seasonEpisodeLabel(strings, null, null))
    }

    @Test
    fun `remaining label subtracts position from runtime`() {
        val runtime = 30L * 60 * 10_000_000
        val position = 18L * 60 * 10_000_000
        assertEquals("12m left", CardFormatting.remainingLabel(strings, runtime, position))
    }

    @Test
    fun `remaining label drops left when unstarted`() {
        // Unstarted (positionTicks == 0) shows plain runtime, never a "left" suffix.
        val runtime = 42L * 60 * 10_000_000
        assertEquals("42m", CardFormatting.remainingLabel(strings, runtime, 0))
    }

    @Test
    fun `remaining label is null with no runtime`() {
        assertNull(CardFormatting.remainingLabel(strings, null, 0))
    }

    @Test
    fun `virtual status label is airs date for a future premiere`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val future = "2026-01-31T00:00:00Z"
        assertEquals("Airs Jan 31", CardFormatting.virtualStatusLabel(strings, future, now, Locale.US))
    }

    @Test
    fun `virtual status label is missing for a past premiere`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val past = "2025-01-01T00:00:00Z"
        assertEquals("Missing", CardFormatting.virtualStatusLabel(strings, past, now))
    }

    @Test
    fun `virtual status label is missing with no premiere date at all`() {
        assertEquals("Missing", CardFormatting.virtualStatusLabel(strings, null))
    }

    @Test
    fun `virtual status label is missing for an unparseable date`() {
        assertEquals("Missing", CardFormatting.virtualStatusLabel(strings, "not-a-date"))
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
        assertEquals("S3 E9 · 12m left", CardFormatting.resumeMetaLine(strings, card))
    }

    @Test
    fun `resume meta line is just remaining time for a movie`() {
        val card = testCard(
            itemType = "Movie",
            runtimeTicks = 120L * 60 * 10_000_000,
            positionTicks = 90L * 60 * 10_000_000,
        )
        assertEquals("30m left", CardFormatting.resumeMetaLine(strings, card))
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
        assertEquals("S2 E4 · Missing", CardFormatting.resumeMetaLine(strings, card))
    }

    @Test
    fun `resume meta line is null with nothing to show`() {
        val card = testCard(itemType = "Movie")
        assertNull(CardFormatting.resumeMetaLine(strings, card))
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
        assertEquals("S3 E9 · 30m", CardFormatting.resumeMetaLine(strings, card))
    }

    @Test
    fun `resume timing label is the remaining time`() {
        val card = testCard(
            itemType = "Episode",
            runtimeTicks = 30L * 60 * 10_000_000,
            positionTicks = 18L * 60 * 10_000_000,
        )
        assertEquals("12m left", CardFormatting.resumeTimingLabel(strings, card))
    }

    @Test
    fun `resume timing label uses virtual status label for a virtual episode`() {
        val card = testCard(itemType = "Episode", isVirtual = true, premiereDate = null)
        assertEquals("Missing", CardFormatting.resumeTimingLabel(strings, card))
    }

    @Test
    fun `resume timing label is null with nothing to show`() {
        val card = testCard(itemType = "Movie")
        assertNull(CardFormatting.resumeTimingLabel(strings, card))
    }

    @Test
    fun `resume timing label drops left for an unstarted card`() {
        // Next Up shelf thumb pill: positionTicks == 0 there, so reads "55m", never "55m left".
        val card = testCard(itemType = "Episode", runtimeTicks = 55L * 60 * 10_000_000, positionTicks = 0)
        assertEquals("55m", CardFormatting.resumeTimingLabel(strings, card))
    }

    @Test
    fun `resume series season line joins series name and season`() {
        val card = testCard(itemType = "Episode", seriesName = "Firefly", parentIndexNumber = 7)
        assertEquals("Firefly · S7", CardFormatting.resumeSeriesSeasonLine(strings, card))
    }

    @Test
    fun `resume series season line drops a missing season number`() {
        val card = testCard(itemType = "Episode", seriesName = "Firefly", parentIndexNumber = null)
        assertEquals("Firefly", CardFormatting.resumeSeriesSeasonLine(strings, card))
    }

    @Test
    fun `resume series season line drops a missing series name`() {
        val card = testCard(itemType = "Episode", seriesName = null, parentIndexNumber = 7)
        assertEquals("S7", CardFormatting.resumeSeriesSeasonLine(strings, card))
    }

    @Test
    fun `resume series season line is null for a movie`() {
        val card = testCard(itemType = "Movie", seriesName = "Firefly", parentIndexNumber = 7)
        assertNull(CardFormatting.resumeSeriesSeasonLine(strings, card))
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
        assertEquals(WatchIndicator.Count(4), CardFormatting.watchIndicator(card, null))
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

    // docs/07 §1: the resume row is uniform 16:9; movies use their backdrop, episodes their
    // screen cap (or the series' art in series-thumb mode).

    @Test
    fun `resume art source uses an episode's rail chain`() {
        val card = testCard(itemType = "Episode", primaryTag = "still-tag")
        assertEquals(CardFormatting.railArtSource(card), CardFormatting.resumeArtSource(card, ResumeArt.EPISODE))
    }

    @Test
    fun `resume art source uses a movie's own backdrop`() {
        val card = testCard(itemType = "Movie", backdropTag = "bd-tag", primaryTag = "poster-tag")
        assertEquals(ArtSource.Own("item-1", "bd-tag", ImageKind.BACKDROP), CardFormatting.resumeArtSource(card, ResumeArt.EPISODE))
    }

    @Test
    fun `resume art source never falls back to a movie's poster`() {
        // No backdrop anywhere: placeholder tile, never a stretched 2:3 poster in the 16:9 slot.
        val card = testCard(itemType = "Movie", primaryTag = "poster-tag")
        assertEquals(ArtSource.None, CardFormatting.resumeArtSource(card, ResumeArt.EPISODE))
    }

    @Test
    fun `series thumb mode routes through the series thumb chain`() {
        val card = testCard(
            itemType = "Episode",
            primaryTag = "still-tag",
            parentThumbItemId = "series-1",
            parentThumbTag = "thumb-tag",
        )
        assertEquals(
            ArtSource.Fallback("series-1", "thumb-tag", ImageKind.THUMB),
            CardFormatting.resumeArtSource(card, ResumeArt.SERIES_THUMB),
        )
    }

    // ---- seriesThumbArtSource (docs/07 §1 spoiler-free chain) -------------

    @Test
    fun `series thumb art uses an episode's parent thumb over its own still`() {
        val card = testCard(
            itemType = "Episode",
            primaryTag = "still-tag",
            parentThumbItemId = "series-1",
            parentThumbTag = "thumb-tag",
            parentBackdropItemId = "series-1",
            parentBackdropTag = "bd-tag",
        )
        assertEquals(ArtSource.Fallback("series-1", "thumb-tag", ImageKind.THUMB), CardFormatting.seriesThumbArtSource(card))
    }

    @Test
    fun `series thumb art falls back to the parent backdrop when an episode has no parent thumb`() {
        val card = testCard(
            itemType = "Episode",
            primaryTag = "still-tag",
            parentBackdropItemId = "series-1",
            parentBackdropTag = "bd-tag",
        )
        assertEquals(ArtSource.Fallback("series-1", "bd-tag", ImageKind.BACKDROP), CardFormatting.seriesThumbArtSource(card))
    }

    @Test
    fun `series thumb art never uses an episode's own still`() {
        val card = testCard(itemType = "Episode", primaryTag = "still-tag", backdropTag = "own-bd-tag")
        assertEquals(ArtSource.None, CardFormatting.seriesThumbArtSource(card))
    }

    @Test
    fun `series thumb art needs both the parent thumb id and tag`() {
        val card = testCard(itemType = "Episode", parentThumbItemId = "series-1")
        assertEquals(ArtSource.None, CardFormatting.seriesThumbArtSource(card))
    }

    @Test
    fun `series thumb art uses a movie's own thumb`() {
        val card = testCard(itemType = "Movie", thumbTag = "thumb-tag", backdropTag = "bd-tag", primaryTag = "poster-tag")
        assertEquals(ArtSource.Own("item-1", "thumb-tag", ImageKind.THUMB), CardFormatting.seriesThumbArtSource(card))
    }

    @Test
    fun `series thumb art falls back to a movie's backdrop`() {
        val card = testCard(itemType = "Movie", backdropTag = "bd-tag", primaryTag = "poster-tag")
        assertEquals(ArtSource.Own("item-1", "bd-tag", ImageKind.BACKDROP), CardFormatting.seriesThumbArtSource(card))
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
        assertEquals("2024 · 1h 30m", CardFormatting.detailMetaLine(strings, 2024, 90L * 60 * 10_000_000))
    }

    @Test
    fun `detail meta line drops a missing runtime`() {
        assertEquals("2024", CardFormatting.detailMetaLine(strings, 2024, null))
    }

    @Test
    fun `detail meta line drops a missing year`() {
        assertEquals("45m", CardFormatting.detailMetaLine(strings, null, 45L * 60 * 10_000_000))
    }

    @Test
    fun `detail meta line is null with neither year nor runtime`() {
        assertNull(CardFormatting.detailMetaLine(strings, null, null))
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
        assertEquals("S3 E9 · 45m · 2024", CardFormatting.heroMetaLine(strings, card))
    }

    @Test
    fun `hero meta line drops missing parts for an episode`() {
        val card = testCard(itemType = "Episode", indexNumber = 9, runtimeTicks = null, productionYear = null)
        assertEquals("E9", CardFormatting.heroMetaLine(strings, card))
    }

    @Test
    fun `hero meta line is null for an episode with nothing to show`() {
        val card = testCard(itemType = "Episode")
        assertNull(CardFormatting.heroMetaLine(strings, card))
    }

    @Test
    fun `hero meta line is just the year for a movie`() {
        val card = testCard(itemType = "Movie", productionYear = 2019)
        assertEquals("2019", CardFormatting.heroMetaLine(strings, card))
    }

    @Test
    fun `hero meta line is null for a movie with no year`() {
        val card = testCard(itemType = "Movie", productionYear = null)
        assertNull(CardFormatting.heroMetaLine(strings, card))
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
        assertEquals(PlayButtonState.Playable, CardFormatting.playButtonState(strings, card))
    }

    @Test
    fun `play button is unavailable with the airs-date label for a future virtual episode`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val card = testCard(isVirtual = true, premiereDate = "2026-01-31T00:00:00Z")
        assertEquals(PlayButtonState.Unavailable("Airs Jan 31"), CardFormatting.playButtonState(strings, card, now, Locale.US))
    }

    @Test
    fun `play button is unavailable with the missing label for a virtual episode with no premiere date`() {
        val card = testCard(isVirtual = true, premiereDate = null)
        assertEquals(PlayButtonState.Unavailable("Missing"), CardFormatting.playButtonState(strings, card))
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
