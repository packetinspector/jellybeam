package tv.jellybeam.ui.detail

import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.testCard
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.AudioSpatialKind
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.MediaStreamInfo
import uniffi.jellybeam_core.MediaStreamKind
import uniffi.jellybeam_core.PersonInfo

/** [MediaStreamInfo] fixture builder; every field defaults to "unknown", same fail-open shape as
 * [testCard]. */
private fun testStream(
    streamType: MediaStreamKind,
    codec: String? = null,
    width: Int? = null,
    height: Int? = null,
    channels: Int? = null,
    bitRate: Int? = null,
    bitDepth: Int? = null,
    isDefault: Boolean = false,
    videoRange: String? = null,
    videoRangeType: String? = null,
    profile: String? = null,
    sampleRate: Int? = null,
    avgFrameRate: Float? = null,
    audioSpatial: AudioSpatialKind? = null,
): MediaStreamInfo = MediaStreamInfo(
    index = null,
    streamType = streamType,
    codec = codec,
    language = null,
    displayTitle = null,
    width = width,
    height = height,
    channels = channels,
    bitRate = bitRate,
    bitDepth = bitDepth,
    isDefault = isDefault,
    videoRange = videoRange,
    videoRangeType = videoRangeType,
    profile = profile,
    sampleRate = sampleRate,
    avgFrameRate = avgFrameRate,
    audioSpatial = audioSpatial,
)

private fun testPerson(
    id: String? = "person-1",
    name: String? = "Name",
    role: String? = null,
    personType: String? = "Actor",
    primaryImageTag: String? = "tag",
): PersonInfo = PersonInfo(id = id, name = name, role = role, personType = personType, primaryImageTag = primaryImageTag)

/** [ItemDetail] fixture builder; every field defaults to "unknown/empty", same fail-open shape as
 * [testCard]. */
private fun testItemDetail(
    id: String = "item-1",
    genres: List<String> = emptyList(),
    officialRating: String? = null,
    communityRating: Float? = null,
    criticRating: Float? = null,
    productionYear: Int? = null,
    endYear: Int? = null,
    status: String? = null,
    studios: List<String> = emptyList(),
    overview: String? = null,
    runTimeTicks: Long? = null,
    container: String? = null,
    people: List<PersonInfo> = emptyList(),
    mediaStreams: List<MediaStreamInfo> = emptyList(),
): ItemDetail = ItemDetail(
    id = id,
    name = "Item",
    itemType = "Movie",
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    premiereDate = null,
    playCount = 0,
    lastPlayedDate = null,
    genres = genres,
    officialRating = officialRating,
    communityRating = communityRating,
    criticRating = criticRating,
    productionYear = productionYear,
    endYear = endYear,
    status = status,
    studios = studios,
    overview = overview,
    runTimeTicks = runTimeTicks,
    container = container,
    people = people,
    mediaStreams = mediaStreams,
    chapters = emptyList(),
    dateCreated = null,
    sizeBytes = null,
    recursiveItemCount = null,
    childCount = null,
    directors = emptyList(),
    writers = emptyList(),
)

class DetailFormattingTest {

    // -- heroPosterArtSource -------------------------------------------

    @Test
    fun `heroPosterArtSource omits the poster slot entirely for an Episode`() {
        val episode = testCard(itemType = "Episode", primaryTag = "own-tag")
        assertNull(DetailFormatting.heroPosterArtSource(episode))
    }

    @Test
    fun `heroPosterArtSource prefers the item's own primary tag`() {
        val movie = testCard(itemType = "Movie", id = "m1", primaryTag = "own-tag")
        assertEquals(
            ArtSource.Own("m1", "own-tag", ImageKind.PRIMARY),
            DetailFormatting.heroPosterArtSource(movie),
        )
    }

    @Test
    fun `heroPosterArtSource falls back to the series primary tag`() {
        val series = testCard(itemType = "Series", primaryTag = null, seriesId = "s1", seriesPrimaryTag = "series-tag")
        assertEquals(
            ArtSource.Fallback("s1", "series-tag", ImageKind.PRIMARY),
            DetailFormatting.heroPosterArtSource(series),
        )
    }

    @Test
    fun `heroPosterArtSource is None when nothing in the chain resolves`() {
        val series = testCard(itemType = "Series", primaryTag = null, seriesId = null, seriesPrimaryTag = null)
        assertEquals(ArtSource.None, DetailFormatting.heroPosterArtSource(series))
    }

    // -- movieMetaLine ---------------------------------------------------

    @Test
    fun `movieMetaLine joins year and runtime with the wide separator`() {
        assertEquals("2020  ·  1h 30m", DetailFormatting.movieMetaLine(2020, 9 * 10_000_000L * 60 * 10))
    }

    @Test
    fun `movieMetaLine drops a missing runtime instead of formatting a stray 0m -- the bug this pass fixes`() {
        assertEquals("2020", DetailFormatting.movieMetaLine(2020, null))
    }

    @Test
    fun `movieMetaLine drops a missing year`() {
        assertEquals("45m", DetailFormatting.movieMetaLine(null, 45 * 60 * 10_000_000L))
    }

    @Test
    fun `movieMetaLine is null when both halves are missing`() {
        assertNull(DetailFormatting.movieMetaLine(null, null))
    }

    // -- seriesMetaLine ---------------------------------------------------

    @Test
    fun `seriesMetaLine never shows runtime and never shows a placeholder 0 seasons`() {
        assertEquals("2020", DetailFormatting.seriesMetaLine(2020, seasonCount = 0))
    }

    @Test
    fun `seriesMetaLine joins the year range and season count for a Continuing series`() {
        assertEquals("2020–  ·  3 seasons", DetailFormatting.seriesMetaLine(2020, seasonCount = 3, status = "Continuing"))
    }

    @Test
    fun `seriesMetaLine singularizes one season`() {
        assertEquals("2020–  ·  1 season", DetailFormatting.seriesMetaLine(2020, seasonCount = 1, status = "Continuing"))
    }

    @Test
    fun `seriesMetaLine shows a real end-year range once ItemDetail resolves one`() {
        assertEquals("2020–2023  ·  3 seasons", DetailFormatting.seriesMetaLine(2020, seasonCount = 3, endYear = 2023, status = "Ended"))
    }

    @Test
    fun `seriesMetaLine with no year shows the season count alone`() {
        assertEquals("3 seasons", DetailFormatting.seriesMetaLine(null, seasonCount = 3))
    }

    @Test
    fun `seriesMetaLine is null with neither a year nor loaded seasons`() {
        assertNull(DetailFormatting.seriesMetaLine(null, seasonCount = 0))
    }

    // -- yearRangeLabel ----------------------------------------------------

    @Test
    fun `yearRangeLabel shows a real range once an end year is known`() {
        assertEquals("2007–2013", DetailFormatting.yearRangeLabel(2007, endYear = 2013, status = "Ended"))
    }

    @Test
    fun `yearRangeLabel trails a dash for a still-Continuing series with no end year yet`() {
        assertEquals("2020–", DetailFormatting.yearRangeLabel(2020, endYear = null, status = "Continuing"))
    }

    @Test
    fun `yearRangeLabel is a bare year for a series that ended without ever getting an EndDate`() {
        assertEquals("2020", DetailFormatting.yearRangeLabel(2020, endYear = null, status = "Ended"))
    }

    @Test
    fun `yearRangeLabel is a bare year before ItemDetail has loaded -- never assumes still-airing`() {
        assertEquals("2020", DetailFormatting.yearRangeLabel(2020))
    }

    @Test
    fun `yearRangeLabel is null with no production year to anchor a range to`() {
        assertNull(DetailFormatting.yearRangeLabel(null, endYear = 2013, status = "Ended"))
    }

    // -- resolvePrimaryAction: Movie/Episode -----------------------------

    @Test
    fun `an unstarted Movie resolves to plain Play`() {
        val movie = testCard(itemType = "Movie", id = "m1", positionTicks = 0)
        val action = DetailFormatting.resolvePrimaryAction(movie, emptyList())
        assertEquals(DetailFormatting.PrimaryAction.Playable("Play", "m1", hasProgress = false), action)
    }

    @Test
    fun `a Movie with progress resolves to Resume and carries hasProgress`() {
        val movie = testCard(itemType = "Movie", id = "m1", positionTicks = 500)
        val action = DetailFormatting.resolvePrimaryAction(movie, emptyList())
        assertEquals(DetailFormatting.PrimaryAction.Playable("Resume", "m1", hasProgress = true), action)
    }

    @Test
    fun `a plain Video resolves to Play and to Resume with progress`() {
        val fresh = testCard(itemType = "Video", id = "v1", positionTicks = 0)
        assertEquals(
            DetailFormatting.PrimaryAction.Playable("Play", "v1", hasProgress = false),
            DetailFormatting.resolvePrimaryAction(fresh, emptyList()),
        )
        val started = testCard(itemType = "Video", id = "v1", positionTicks = 500)
        assertEquals(
            DetailFormatting.PrimaryAction.Playable("Resume", "v1", hasProgress = true),
            DetailFormatting.resolvePrimaryAction(started, emptyList()),
        )
    }

    @Test
    fun `MusicVideo and Recording are playable single items, a folder is not`() {
        for (type in listOf("MusicVideo", "Recording")) {
            val card = testCard(itemType = type, id = "x1", positionTicks = 0)
            assertEquals(DetailFormatting.PrimaryAction.Playable("Play", "x1", hasProgress = false), DetailFormatting.resolvePrimaryAction(card, emptyList()))
        }
        val folder = testCard(itemType = "ChannelFolderItem", id = "f1", positionTicks = 0)
        assertEquals(DetailFormatting.PrimaryAction.None, DetailFormatting.resolvePrimaryAction(folder, emptyList()))
    }

    @Test
    fun `a virtual Episode is Unavailable with the virtual-status label, not Playable`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val episode = testCard(itemType = "Episode", isVirtual = true, premiereDate = "2026-03-04T00:00:00Z")
        val action = DetailFormatting.resolvePrimaryAction(episode, emptyList(), now)
        assertEquals(DetailFormatting.PrimaryAction.Unavailable("Airs Mar 4"), action)
    }

    @Test
    fun `an unknown item type resolves to None`() {
        val boxSet = testCard(itemType = "BoxSet")
        assertEquals(DetailFormatting.PrimaryAction.None, DetailFormatting.resolvePrimaryAction(boxSet, emptyList()))
    }

    // -- resolvePrimaryAction: Series -------------------------------------

    @Test
    fun `a Series resumes the first in-progress episode with a full S-E label`() {
        val series = testCard(itemType = "Series")
        val resuming = testCard(id = "ep-6", itemType = "Episode", parentIndexNumber = 2, indexNumber = 6, positionTicks = 100)
        val action = DetailFormatting.resolvePrimaryAction(series, listOf(resuming))
        assertEquals(DetailFormatting.PrimaryAction.Playable("Resume S2 E6", "ep-6", hasProgress = true), action)
    }

    @Test
    fun `a Series resume target missing either S or E number degrades to plain Resume, never S question E`() {
        val series = testCard(itemType = "Series")
        val resuming = testCard(id = "ep-6", itemType = "Episode", parentIndexNumber = null, indexNumber = 6, positionTicks = 100)
        val action = DetailFormatting.resolvePrimaryAction(series, listOf(resuming))
        assertEquals(DetailFormatting.PrimaryAction.Playable("Resume", "ep-6", hasProgress = true), action)
    }

    @Test
    fun `a Series with nothing in progress falls to the first unplayed episode as plain Play`() {
        val series = testCard(itemType = "Series")
        val watched = testCard(id = "ep-1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true)
        val unplayed = testCard(id = "ep-2", itemType = "Episode", parentIndexNumber = 1, indexNumber = 2, played = false)
        val action = DetailFormatting.resolvePrimaryAction(series, listOf(watched, unplayed))
        assertEquals(DetailFormatting.PrimaryAction.Playable("Play", "ep-2", hasProgress = false), action)
    }

    @Test
    fun `a Series with an unplayed Special earlier in the list still prefers a later non-special episode`() {
        val series = testCard(itemType = "Series")
        val special = testCard(id = "sp-1", itemType = "Episode", parentIndexNumber = 0, indexNumber = 1, played = false)
        val normal = testCard(id = "ep-1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = false)
        val action = DetailFormatting.resolvePrimaryAction(series, listOf(special, normal))
        assertEquals(DetailFormatting.PrimaryAction.Playable("Play", "ep-1", hasProgress = false), action)
    }

    @Test
    fun `a Series skips a virtual episode even if it has progress`() {
        val series = testCard(itemType = "Series")
        val virtualResuming = testCard(
            id = "virt-1",
            itemType = "Episode",
            parentIndexNumber = 1,
            indexNumber = 1,
            positionTicks = 100,
            isVirtual = true,
        )
        val unplayed = testCard(id = "ep-2", itemType = "Episode", parentIndexNumber = 1, indexNumber = 2, played = false)
        val action = DetailFormatting.resolvePrimaryAction(series, listOf(virtualResuming, unplayed))
        assertEquals(DetailFormatting.PrimaryAction.Playable("Play", "ep-2", hasProgress = false), action)
    }

    @Test
    fun `a Series with every loaded episode already played and none in progress resolves to None`() {
        val series = testCard(itemType = "Series")
        val watched = testCard(id = "ep-1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true)
        assertEquals(DetailFormatting.PrimaryAction.None, DetailFormatting.resolvePrimaryAction(series, listOf(watched)))
    }

    @Test
    fun `a Series with no loaded episodes resolves to None`() {
        val series = testCard(itemType = "Series")
        assertEquals(DetailFormatting.PrimaryAction.None, DetailFormatting.resolvePrimaryAction(series, emptyList()))
    }

    @Test
    fun `docs-11 item 11 cross-season fix -- resume target in a later season still wins over an earlier season's episodes`() {
        val series = testCard(itemType = "Series")
        val season1Watched = testCard(id = "s1e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true)
        val season2Resuming = testCard(id = "s2e3", itemType = "Episode", parentIndexNumber = 2, indexNumber = 3, positionTicks = 100)
        val action = DetailFormatting.resolvePrimaryAction(series, listOf(season1Watched, season2Resuming))
        assertEquals(DetailFormatting.PrimaryAction.Playable("Resume S2 E3", "s2e3", hasProgress = true), action)
    }

    @Test
    fun `docs-11 item 11 cross-season fix -- next-unplayed in a later season still wins when the selected season is fully watched`() {
        val series = testCard(itemType = "Series")
        val season1Watched = testCard(id = "s1e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true)
        val season2Unplayed = testCard(id = "s2e1", itemType = "Episode", parentIndexNumber = 2, indexNumber = 1, played = false)
        val action = DetailFormatting.resolvePrimaryAction(series, listOf(season1Watched, season2Unplayed))
        assertEquals(DetailFormatting.PrimaryAction.Playable("Play", "s2e1", hasProgress = false), action)
    }

    // -- defaultSeason / seasonsWithSpecialsLast --------------------------

    @Test
    fun `defaultSeason skips a Specials season named exactly Specials`() {
        val specials = testCard(id = "s0", itemType = "Season", indexNumber = 0, name = "Specials")
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1, name = "Season 1")
        assertEquals(season1, DetailFormatting.defaultSeason(listOf(specials, season1)))
    }

    @Test
    fun `defaultSeason skips a season indexed 0 even if not named Specials`() {
        val season0 = testCard(id = "s0", itemType = "Season", indexNumber = 0, name = "Odd server data")
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1, name = "Season 1")
        assertEquals(season1, DetailFormatting.defaultSeason(listOf(season0, season1)))
    }

    @Test
    fun `defaultSeason falls back to the first season when every season is special`() {
        val specials = testCard(id = "s0", itemType = "Season", indexNumber = 0, name = "Specials")
        assertEquals(specials, DetailFormatting.defaultSeason(listOf(specials)))
    }

    @Test
    fun `defaultSeason is null for an empty season list`() {
        assertNull(DetailFormatting.defaultSeason(emptyList()))
    }

    @Test
    fun `seasonsWithSpecialsLast moves Specials to the end and keeps the rest in order`() {
        val specials = testCard(id = "s0", itemType = "Season", indexNumber = 0, name = "Specials")
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1, name = "Season 1")
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2, name = "Season 2")

        val ordered = DetailFormatting.seasonsWithSpecialsLast(listOf(specials, season1, season2))

        assertEquals(listOf("s1", "s2", "s0"), ordered.map { it.id })
    }

    @Test
    fun `seasonsWithSpecialsLast is a no-op when there's no Specials season`() {
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2)
        assertTrue(DetailFormatting.seasonsWithSpecialsLast(listOf(season1, season2)) == listOf(season1, season2))
    }

    // -- resolveResumeSeason (series page resume-season preselection) ----

    @Test
    fun `resolveResumeSeason lands on the season holding an in-progress episode mid-series`() {
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2)
        val season3 = testCard(id = "s3", itemType = "Season", indexNumber = 3)
        val s1e1 = testCard(id = "s1e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true)
        val s2e2 = testCard(id = "s2e2", itemType = "Episode", parentIndexNumber = 2, indexNumber = 2, positionTicks = 500)
        val s3e1 = testCard(id = "s3e1", itemType = "Episode", parentIndexNumber = 3, indexNumber = 1, played = false)
        val resolved = DetailFormatting.resolveResumeSeason(listOf(season1, season2, season3), listOf(s1e1, s2e2, s3e1))
        assertEquals(season2, resolved)
    }

    @Test
    fun `resolveResumeSeason falls back to the first season for a fully-watched series -- documented, no rewatch target spec'd`() {
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2)
        val s1e1 = testCard(id = "s1e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = true)
        val s2e1 = testCard(id = "s2e1", itemType = "Episode", parentIndexNumber = 2, indexNumber = 1, played = true)
        val resolved = DetailFormatting.resolveResumeSeason(listOf(season1, season2), listOf(s1e1, s2e1))
        assertEquals(season1, resolved)
    }

    @Test
    fun `resolveResumeSeason lands on season 1 when nothing has been watched at all`() {
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1)
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2)
        val s1e1 = testCard(id = "s1e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, played = false)
        val s2e1 = testCard(id = "s2e1", itemType = "Episode", parentIndexNumber = 2, indexNumber = 1, played = false)
        val resolved = DetailFormatting.resolveResumeSeason(listOf(season1, season2), listOf(s1e1, s2e1))
        assertEquals(season1, resolved)
    }

    @Test
    fun `resolveResumeSeason on a single-season series always returns that one season`() {
        val onlySeason = testCard(id = "s1", itemType = "Season", indexNumber = 1)
        val episode = testCard(id = "e1", itemType = "Episode", parentIndexNumber = 1, indexNumber = 1, positionTicks = 200)
        assertEquals(onlySeason, DetailFormatting.resolveResumeSeason(listOf(onlySeason), listOf(episode)))
        assertEquals(onlySeason, DetailFormatting.resolveResumeSeason(listOf(onlySeason), emptyList()))
    }

    @Test
    fun `resolveResumeSeason with no per-episode userdata yet falls back to each season's own unplayedCount`() {
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1, unplayedCount = 0)
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2, unplayedCount = 4)
        assertEquals(season2, DetailFormatting.resolveResumeSeason(listOf(season1, season2), emptyList()))
    }

    @Test
    fun `resolveResumeSeason treats a missing unplayedCount as eligible, not as fully watched`() {
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1, unplayedCount = null)
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2, unplayedCount = null)
        assertEquals(season1, DetailFormatting.resolveResumeSeason(listOf(season1, season2), emptyList()))
    }

    @Test
    fun `resolveResumeSeason skips a Specials season for the per-season fallback signal`() {
        val specials = testCard(id = "s0", itemType = "Season", indexNumber = 0, name = "Specials", unplayedCount = 3)
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1, unplayedCount = 0)
        val season2 = testCard(id = "s2", itemType = "Season", indexNumber = 2, unplayedCount = 5)
        assertEquals(season2, DetailFormatting.resolveResumeSeason(listOf(specials, season1, season2), emptyList()))
    }

    @Test
    fun `resolveResumeSeason is null for an empty season list`() {
        assertNull(DetailFormatting.resolveResumeSeason(emptyList(), emptyList()))
    }

    @Test
    fun `resolveResumeSeason falls back to defaultSeason when the resume episode's season isn't in the loaded list`() {
        val season1 = testCard(id = "s1", itemType = "Season", indexNumber = 1)
        val orphanEpisode = testCard(id = "e9", itemType = "Episode", parentIndexNumber = 9, indexNumber = 1, positionTicks = 100)
        assertEquals(season1, DetailFormatting.resolveResumeSeason(listOf(season1), listOf(orphanEpisode)))
    }

    // -- classify (spec strip emphasis tiers) -----------------------------

    @Test
    fun `classify tags an ordinary value baseline`() {
        assertEquals(DetailFormatting.SpecWeight.BASELINE, DetailFormatting.classify("1080P"))
        assertEquals(DetailFormatting.SpecWeight.BASELINE, DetailFormatting.classify("H264"))
        assertEquals(DetailFormatting.SpecWeight.BASELINE, DetailFormatting.classify("AC3"))
        assertEquals(DetailFormatting.SpecWeight.BASELINE, DetailFormatting.classify("MKV"))
    }

    @Test
    fun `classify tags 4K, HDR, and HEVC notable`() {
        assertEquals(DetailFormatting.SpecWeight.NOTABLE, DetailFormatting.classify("4K"))
        assertEquals(DetailFormatting.SpecWeight.NOTABLE, DetailFormatting.classify("HDR10"))
        assertEquals(DetailFormatting.SpecWeight.NOTABLE, DetailFormatting.classify("HEVC"))
    }

    @Test
    fun `classify tags lossless codec names notable, matched as whole words`() {
        assertEquals(DetailFormatting.SpecWeight.NOTABLE, DetailFormatting.classify("FLAC STEREO"))
        // "PCMA" contains "PCM" but isn't a whole-word match.
        assertEquals(DetailFormatting.SpecWeight.BASELINE, DetailFormatting.classify("PCMA"))
    }

    @Test
    fun `classify tags Dolby Vision, Atmos, and 10-bit-plus best-in-class`() {
        assertEquals(DetailFormatting.SpecWeight.BEST_IN_CLASS, DetailFormatting.classify("DOLBY VISION"))
        assertEquals(DetailFormatting.SpecWeight.BEST_IN_CLASS, DetailFormatting.classify("TRUEHD 7.1 ATMOS"))
        assertEquals(DetailFormatting.SpecWeight.BEST_IN_CLASS, DetailFormatting.classify("10-BIT"))
        assertEquals(DetailFormatting.SpecWeight.BEST_IN_CLASS, DetailFormatting.classify("12-BIT"))
    }

    @Test
    fun `classify never promotes 8-bit -- only 10-bit and up`() {
        assertEquals(DetailFormatting.SpecWeight.BASELINE, DetailFormatting.classify("8-BIT"))
    }

    // -- resolutionLabel ---------------------------------------------------

    @Test
    fun `resolutionLabel classifies a widescreen crop by width, not height`() {
        assertEquals("1080P", DetailFormatting.resolutionLabel(1920, 960))
        assertEquals("1080P", DetailFormatting.resolutionLabel(1920, 800))
        assertEquals("4K", DetailFormatting.resolutionLabel(3840, 1600))
    }

    @Test
    fun `resolutionLabel still promotes 4-3 content by height`() {
        assertEquals("1080P", DetailFormatting.resolutionLabel(1440, 1080))
    }

    @Test
    fun `resolutionLabel is null with no usable height`() {
        assertNull(DetailFormatting.resolutionLabel(1920, null))
        assertNull(DetailFormatting.resolutionLabel(1920, 0))
    }

    // -- hdrLabel -----------------------------------------------------------

    @Test
    fun `hdrLabel collapses every Dolby Vision variant to one label`() {
        assertEquals("DOLBY VISION", DetailFormatting.hdrLabel(null, "DOVI"))
        assertEquals("DOLBY VISION", DetailFormatting.hdrLabel(null, "DOVIWithHDR10"))
    }

    @Test
    fun `hdrLabel reads HDR10, HDR10Plus and HLG from the precise type field`() {
        assertEquals("HDR10", DetailFormatting.hdrLabel("HDR", "HDR10"))
        assertEquals("HDR10+", DetailFormatting.hdrLabel("HDR", "HDR10Plus"))
        assertEquals("HLG", DetailFormatting.hdrLabel("HDR", "HLG"))
    }

    @Test
    fun `hdrLabel falls back to the coarse videoRange when there's no precise type`() {
        assertEquals("HDR", DetailFormatting.hdrLabel("HDR", null))
    }

    @Test
    fun `hdrLabel is null for SDR content`() {
        assertNull(DetailFormatting.hdrLabel("SDR", null))
        assertNull(DetailFormatting.hdrLabel(null, null))
    }

    // -- channelLabel / audioLabel -------------------------------------------

    @Test
    fun `channelLabel names common layouts, else falls back to a bare count`() {
        assertEquals("STEREO", DetailFormatting.channelLabel(2))
        assertEquals("5.1", DetailFormatting.channelLabel(6))
        assertEquals("7.1", DetailFormatting.channelLabel(8))
        assertEquals("3 CH", DetailFormatting.channelLabel(3))
        assertNull(DetailFormatting.channelLabel(null))
    }

    @Test
    fun `audioLabel appends the channel layout to the codec name`() {
        assertEquals("AAC 5.1", DetailFormatting.audioLabel("aac", 6))
    }

    @Test
    fun `audioLabel is bare codec when the channel count is unknown`() {
        assertEquals("TRUEHD", DetailFormatting.audioLabel("truehd", null))
    }

    @Test
    fun `audioLabel is null with no codec`() {
        assertNull(DetailFormatting.audioLabel(null, 2))
    }

    @Test
    fun `audioLabel appends the object-based format after the channels`() {
        assertEquals("TRUEHD 7.1 ATMOS", DetailFormatting.audioLabel("truehd", 8, AudioSpatialKind.DOLBY_ATMOS))
        assertEquals("EAC3 5.1 ATMOS", DetailFormatting.audioLabel("eac3", 6, AudioSpatialKind.DOLBY_ATMOS))
        assertEquals("DTS 7.1 DTS:X", DetailFormatting.audioLabel("dts", 8, AudioSpatialKind.DTS_X))
    }

    @Test
    fun `a spatial audio stream lands the strip's audio cell in the best-in-class tier`() {
        val detail = testItemDetail(
            mediaStreams = listOf(
                testStream(MediaStreamKind.AUDIO, codec = "eac3", channels = 6, isDefault = true, audioSpatial = AudioSpatialKind.DOLBY_ATMOS),
            ),
        )

        val audio = DetailFormatting.specStripFields(detail).single()

        assertEquals("EAC3 5.1 ATMOS", audio.value)
        assertEquals(DetailFormatting.SpecWeight.BEST_IN_CLASS, audio.weight)
    }

    // -- specStripFields -----------------------------------------------------

    @Test
    fun `specStripFields follows the spec's field order and omits SIZE outright`() {
        val detail = testItemDetail(
            container = "mkv",
            mediaStreams = listOf(
                testStream(
                    MediaStreamKind.VIDEO, codec = "hevc", width = 1920, height = 1080,
                    bitDepth = 10, bitRate = 8_500_000, videoRangeType = "HDR10",
                ),
                testStream(MediaStreamKind.AUDIO, codec = "aac", channels = 6, isDefault = true),
            ),
        )
        val values = DetailFormatting.specStripFields(detail).map { it.value }
        assertEquals(listOf("1080P", "HEVC", "10-BIT", "HDR10", "AAC 5.1", "8.5 MBPS", "MKV"), values)
    }

    @Test
    fun `specStripFields omits unknown fields rather than placeholdering them`() {
        val detail = testItemDetail(
            mediaStreams = listOf(testStream(MediaStreamKind.VIDEO, codec = "h264")),
        )
        assertEquals(listOf("H264"), DetailFormatting.specStripFields(detail).map { it.value })
    }

    @Test
    fun `specStripFields is empty for a Series -- no MediaStreams to read`() {
        assertTrue(DetailFormatting.specStripFields(testItemDetail()).isEmpty())
    }

    @Test
    fun `specStripFields prefers the default-flagged audio track over the first`() {
        val detail = testItemDetail(
            mediaStreams = listOf(
                testStream(MediaStreamKind.AUDIO, codec = "ac3", channels = 2),
                testStream(MediaStreamKind.AUDIO, codec = "dts", channels = 6, isDefault = true),
            ),
        )
        assertEquals(listOf("DTS 5.1"), DetailFormatting.specStripFields(detail).map { it.value })
    }

    @Test
    fun `specStripFields carries each field's classify tier`() {
        val detail = testItemDetail(
            mediaStreams = listOf(testStream(MediaStreamKind.VIDEO, width = 3840, height = 2160)),
        )
        val fields = DetailFormatting.specStripFields(detail)
        assertEquals(DetailFormatting.SpecWeight.NOTABLE, fields.single().weight)
    }

    // -- detailsFooterFields / detailsFooterLine ------------------------------

    @Test
    fun `detailsFooterFields orders genres, studios, rating, then year for a Movie`() {
        val fields = DetailFormatting.detailsFooterFields(
            itemType = "Movie",
            genres = listOf("Comedy", "Drama"),
            studios = listOf("Studio One"),
            officialRating = "PG-13",
            productionYear = 2020,
            endYear = null,
            status = null,
        )
        assertEquals(listOf("Comedy, Drama", "Studio One", "PG-13", "2020"), fields)
    }

    @Test
    fun `detailsFooterFields never shows genres for a Series`() {
        val fields = DetailFormatting.detailsFooterFields(
            itemType = "Series",
            genres = listOf("Comedy"),
            studios = emptyList(),
            officialRating = null,
            productionYear = 2020,
            endYear = 2023,
            status = "Ended",
        )
        assertEquals(listOf("2020–2023"), fields)
    }

    @Test
    fun `detailsFooterFields drops every empty field rather than placeholdering it`() {
        assertTrue(
            DetailFormatting.detailsFooterFields(
                itemType = "Movie",
                genres = emptyList(),
                studios = emptyList(),
                officialRating = null,
                productionYear = null,
                endYear = null,
                status = null,
            ).isEmpty(),
        )
    }

    @Test
    fun `detailsFooterLine joins fields with the spec separator, uppercased`() {
        val line = DetailFormatting.detailsFooterLine(
            itemType = "Movie",
            genres = listOf("Comedy"),
            studios = listOf("Studio One"),
            officialRating = "pg-13",
            productionYear = 2020,
            endYear = null,
            status = null,
        )
        assertEquals("COMEDY │ STUDIO ONE │ PG-13 │ 2020", line)
    }

    @Test
    fun `detailsFooterLine is null when every field is absent`() {
        assertNull(
            DetailFormatting.detailsFooterLine(
                itemType = "Movie",
                genres = emptyList(),
                studios = emptyList(),
                officialRating = null,
                productionYear = null,
                endYear = null,
                status = null,
            ),
        )
    }

    // -- castMembers ---------------------------------------------------------

    @Test
    fun `castMembers skips anyone missing an id or a portrait -- never a gray placeholder circle`() {
        val withPortrait = testPerson(id = "p1", primaryImageTag = "tag1")
        val noPortrait = testPerson(id = "p2", primaryImageTag = null)
        val noId = testPerson(id = null, primaryImageTag = "tag3")

        assertEquals(listOf(withPortrait), DetailFormatting.castMembers(listOf(withPortrait, noPortrait, noId)))
    }

    @Test
    fun `castMembers is empty, not a crash, when nobody has a portrait`() {
        assertTrue(DetailFormatting.castMembers(listOf(testPerson(primaryImageTag = null))).isEmpty())
    }

    // -- dedupeById (bug 1: crash-safe lazy-container keys) ------------------

    @Test
    fun `dedupeById keeps the first occurrence of a repeated id`() {
        val first = testCard(id = "dup", name = "First")
        val second = testCard(id = "dup", name = "Second")
        val unique = testCard(id = "unique")
        val result = DetailFormatting.dedupeById(listOf(first, second, unique)) { it.id }
        assertEquals(listOf(first, unique), result)
    }

    @Test
    fun `dedupeById is a no-op when every id is already unique`() {
        val a = testCard(id = "a")
        val b = testCard(id = "b")
        assertEquals(listOf(a, b), DetailFormatting.dedupeById(listOf(a, b)) { it.id })
    }

    @Test
    fun `dedupeById on an empty list is empty, not a crash`() {
        assertTrue(DetailFormatting.dedupeById(emptyList<uniffi.jellybeam_core.Card>()) { it.id }.isEmpty())
    }

    // -- containerLabel (bug 7: spec strip CONTAINER cell) --------------------

    @Test
    fun `containerLabel takes only the first candidate from the server's whole compatibility list`() {
        assertEquals("MOV", DetailFormatting.containerLabel("mov,mp4,m4a,3gp,3g2,mj2"))
    }

    @Test
    fun `containerLabel uppercases and trims a single-value container`() {
        assertEquals("MKV", DetailFormatting.containerLabel(" mkv "))
    }

    @Test
    fun `containerLabel is null for a missing or blank container`() {
        assertNull(DetailFormatting.containerLabel(null))
        assertNull(DetailFormatting.containerLabel("  "))
    }

    // -- seriesCardFrom (bug 8: Episode page "View Series" navigation) -------

    @Test
    fun `seriesCardFrom builds a Series card from an episode's own series fields`() {
        val episode = testCard(
            id = "ep-1",
            itemType = "Episode",
            seriesId = "series-1",
            seriesName = "The Show",
            seriesPrimaryTag = "series-tag",
            libraryId = "lib-1",
        )
        val seriesCard = DetailFormatting.seriesCardFrom(episode)
        assertEquals("series-1", seriesCard?.id)
        assertEquals("Series", seriesCard?.itemType)
        assertEquals("The Show", seriesCard?.name)
        assertEquals("lib-1", seriesCard?.libraryId)
        assertEquals(
            ArtSource.Own("series-1", "series-tag", ImageKind.PRIMARY),
            seriesCard?.let { DetailFormatting.heroPosterArtSource(it) },
        )
    }

    @Test
    fun `seriesCardFrom carries the episode's parent-backdrop pair so the View-Series page isn't art-less`() {
        // §D.1: parentBackdrop fields carry the series' own backdrop art through to the card.
        val episode = testCard(
            id = "ep-1",
            itemType = "Episode",
            seriesId = "series-1",
            seriesName = "The Show",
            parentBackdropItemId = "series-1",
            parentBackdropTag = "series-backdrop-tag",
        )
        val seriesCard = DetailFormatting.seriesCardFrom(episode)
        assertEquals("series-1", seriesCard?.parentBackdropItemId)
        assertEquals("series-backdrop-tag", seriesCard?.parentBackdropTag)
        assertEquals(
            ArtSource.Fallback("series-1", "series-backdrop-tag", ImageKind.BACKDROP),
            seriesCard?.let { tv.jellybeam.ui.cards.CardFormatting.backdropArtSource(it) },
        )
    }

    @Test
    fun `seriesCardFrom is null when the episode carries no seriesId at all`() {
        val episode = testCard(id = "ep-1", itemType = "Episode", seriesId = null)
        assertNull(DetailFormatting.seriesCardFrom(episode))
    }

    @Test
    fun `seriesCardFrom never carries an overview -- bug 3, DetailScreen's SeriesDetailScreen must read detail first`() {
        // The synthesized card carries no overview; DetailScreen must read `detail?.overview`
        // first and fall back to `card.overview` only for an ordinarily-reached series.
        val episode = testCard(id = "ep-1", itemType = "Episode", seriesId = "series-1", seriesName = "The Show", overview = "An episode synopsis.")
        val seriesCard = DetailFormatting.seriesCardFrom(episode)
        assertNull(seriesCard?.overview)
    }

    // -- below-fold section reveal gating --------

    @Test
    fun `specStripReady is immediate for a Series regardless of itemDetail's own load state`() {
        assertTrue(DetailFormatting.specStripReady("Series", itemDetailLoaded = false))
        assertTrue(DetailFormatting.specStripReady("Series", itemDetailLoaded = true))
    }

    @Test
    fun `specStripReady for a Movie or Episode waits on the itemDetail fetch settling`() {
        assertEquals(false, DetailFormatting.specStripReady("Movie", itemDetailLoaded = false))
        assertTrue(DetailFormatting.specStripReady("Movie", itemDetailLoaded = true))
        assertEquals(false, DetailFormatting.specStripReady("Episode", itemDetailLoaded = false))
        assertTrue(DetailFormatting.specStripReady("Episode", itemDetailLoaded = true))
    }

    @Test
    fun `showEpisodeSkeleton is true for a Series while either seasons or episodes are loading`() {
        assertTrue(DetailFormatting.showEpisodeSkeleton(isSeries = true, isLoadingSeasons = true, isLoadingEpisodes = false))
        assertTrue(DetailFormatting.showEpisodeSkeleton(isSeries = true, isLoadingSeasons = false, isLoadingEpisodes = true))
        assertTrue(DetailFormatting.showEpisodeSkeleton(isSeries = true, isLoadingSeasons = true, isLoadingEpisodes = true))
    }

    @Test
    fun `showEpisodeSkeleton is false once a Series has settled -- neither seasons nor episodes loading`() {
        assertEquals(
            false,
            DetailFormatting.showEpisodeSkeleton(isSeries = true, isLoadingSeasons = false, isLoadingEpisodes = false),
        )
    }

    @Test
    fun `showEpisodeSkeleton never applies to a non-Series item, even mid-load`() {
        assertEquals(
            false,
            DetailFormatting.showEpisodeSkeleton(isSeries = false, isLoadingSeasons = true, isLoadingEpisodes = true),
        )
    }

    // -- shortDate / runtimeAndDateLine --------------------------------------

    @Test
    fun `shortDate formats an RFC3339 timestamp as a short US date`() {
        assertEquals("Mar 6, 2014", DetailFormatting.shortDate("2014-03-06T00:00:00Z"))
    }

    @Test
    fun `shortDate is null for an absent or unparseable timestamp`() {
        assertNull(DetailFormatting.shortDate(null))
        assertNull(DetailFormatting.shortDate("not a date"))
    }

    @Test
    fun `runtimeAndDateLine joins runtime and air date with the thin separator`() {
        assertEquals("20m · Mar 6, 2014", DetailFormatting.runtimeAndDateLine(20 * 60 * 10_000_000L, "2014-03-06T00:00:00Z"))
    }

    @Test
    fun `runtimeAndDateLine drops a missing date`() {
        assertEquals("20m", DetailFormatting.runtimeAndDateLine(20 * 60 * 10_000_000L, null))
    }

    @Test
    fun `runtimeAndDateLine drops a missing runtime`() {
        assertEquals("Mar 6, 2014", DetailFormatting.runtimeAndDateLine(null, "2014-03-06T00:00:00Z"))
    }

    @Test
    fun `runtimeAndDateLine is null when both halves are missing`() {
        assertNull(DetailFormatting.runtimeAndDateLine(null, null))
    }

    // -- episodeEyebrow / episodeHeaderLine -----------------------------------

    @Test
    fun `episodeEyebrow joins series name and season-episode label`() {
        assertEquals("Series Alpha · S7 E17", DetailFormatting.episodeEyebrow("Series Alpha", 7, 17))
    }

    @Test
    fun `episodeEyebrow drops a missing series name`() {
        assertEquals("S7 E17", DetailFormatting.episodeEyebrow(null, 7, 17))
    }

    @Test
    fun `episodeEyebrow is null when both halves are missing`() {
        assertNull(DetailFormatting.episodeEyebrow(null, null, null))
    }

    @Test
    fun `episodeHeaderLine joins year, runtime, rating and genres`() {
        assertEquals(
            "2014  ·  20m  ·  TV-PG  ·  Comedy, Romance",
            DetailFormatting.episodeHeaderLine(2014, 20 * 60 * 10_000_000L, "TV-PG", listOf("Comedy", "Romance")),
        )
    }

    @Test
    fun `episodeHeaderLine omits every absent part rather than placeholdering it`() {
        assertNull(DetailFormatting.episodeHeaderLine(null, null, null, emptyList()))
    }

    @Test
    fun `episodeHeaderItems is the same parts episodeHeaderLine joins, unjoined`() {
        assertEquals(
            listOf("2014", "20m", "TV-PG", "Comedy, Romance"),
            DetailFormatting.episodeHeaderItems(2014, 20 * 60 * 10_000_000L, "TV-PG", listOf("Comedy", "Romance")),
        )
        assertEquals(emptyList<String>(), DetailFormatting.episodeHeaderItems(null, null, null, emptyList()))
    }

    // -- seriesHeaderLine ------------------------------------------------------

    @Test
    fun `seriesHeaderLine joins year range, season and episode counts, rating and up to two genres`() {
        assertEquals(
            "2007–2019  ·  13 seasons  ·  279 episodes  ·  TV-PG  ·  Comedy, Drama",
            DetailFormatting.seriesHeaderLine(
                productionYear = 2007,
                endYear = 2019,
                status = "Ended",
                seasonCount = 13,
                episodeCount = 279,
                officialRating = "TV-PG",
                genres = listOf("Comedy", "Drama", "Family"),
            ),
        )
    }

    @Test
    fun `seriesHeaderLine singularizes a single season and episode`() {
        assertEquals(
            "2020  ·  1 season  ·  1 episode",
            DetailFormatting.seriesHeaderLine(2020, null, null, 1, 1, null, emptyList()),
        )
    }

    @Test
    fun `seriesHeaderLine omits season and episode counts before ItemDetail resolves them`() {
        assertEquals("2020", DetailFormatting.seriesHeaderLine(2020, null, null, null, null, null, emptyList()))
    }

    @Test
    fun `seriesHeaderLine is null when every part is absent`() {
        assertNull(DetailFormatting.seriesHeaderLine(null, null, null, null, null, null, emptyList()))
    }

    @Test
    fun `seriesHeaderItems is the same parts seriesHeaderLine joins, unjoined`() {
        assertEquals(
            listOf("2007–2019", "13 seasons", "279 episodes", "TV-PG", "Comedy, Drama"),
            DetailFormatting.seriesHeaderItems(
                productionYear = 2007,
                endYear = 2019,
                status = "Ended",
                seasonCount = 13,
                episodeCount = 279,
                officialRating = "TV-PG",
                genres = listOf("Comedy", "Drama", "Family"),
            ),
        )
        assertEquals(emptyList<String>(), DetailFormatting.seriesHeaderItems(null, null, null, null, null, null, emptyList()))
    }

    // -- seasonSummaryLine -------------------------------------------------

    @Test
    fun `seasonSummaryLine uppercases the season name and counts watched episodes`() {
        val watched1 = testCard(id = "e1", itemType = "Episode", played = true)
        val watched2 = testCard(id = "e2", itemType = "Episode", played = true)
        val unwatched = testCard(id = "e3", itemType = "Episode", played = false)
        assertEquals(
            "SEASON 1 · 3 EPISODES · 2 WATCHED",
            DetailFormatting.seasonSummaryLine("Season 1", listOf(watched1, watched2, unwatched)),
        )
    }

    @Test
    fun `seasonSummaryLine handles zero watched episodes`() {
        val unwatched = testCard(id = "e1", itemType = "Episode", played = false)
        assertEquals("SPECIALS · 1 EPISODE · 0 WATCHED", DetailFormatting.seasonSummaryLine("Specials", listOf(unwatched)))
    }

    @Test
    fun `seasonSummaryItems is the same three parts seasonSummaryLine joins, unjoined`() {
        val watched = testCard(id = "e1", itemType = "Episode", played = true)
        val unwatched = testCard(id = "e2", itemType = "Episode", played = false)
        assertEquals(
            listOf("SEASON 1", "2 EPISODES", "1 WATCHED"),
            DetailFormatting.seasonSummaryItems("Season 1", listOf(watched, unwatched)),
        )
    }

    // -- showSeasonsLabel (§D.5 season-selector one-row fix) --------------

    @Test
    fun `showSeasonsLabel is true for a typical season count`() {
        assertTrue(DetailFormatting.showSeasonsLabel(1))
        assertTrue(DetailFormatting.showSeasonsLabel(10))
    }

    @Test
    fun `showSeasonsLabel drops past the many-seasons threshold`() {
        assertEquals(false, DetailFormatting.showSeasonsLabel(11))
        assertEquals(false, DetailFormatting.showSeasonsLabel(25))
    }

    // -- formatFileSize --------------------------------------------------------

    @Test
    fun `formatFileSize renders one decimal GB above the 1024MB threshold`() {
        assertEquals("7.2 GB", DetailFormatting.formatFileSize((7.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `formatFileSize renders a whole-number MB count below the GB threshold`() {
        assertEquals("512 MB", DetailFormatting.formatFileSize(512L * 1024 * 1024))
    }

    @Test
    fun `formatFileSize is null for an absent or non-positive size`() {
        assertNull(DetailFormatting.formatFileSize(null))
        assertNull(DetailFormatting.formatFileSize(0L))
        assertNull(DetailFormatting.formatFileSize(-5L))
    }

    // -- relativeAddedClause / movieEyebrow -------------------------------------

    @Test
    fun `relativeAddedClause reports Today for a same-day timestamp`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals("Today", DetailFormatting.relativeAddedClause("2026-08-29T02:00:00Z", now))
    }

    @Test
    fun `relativeAddedClause reports Yesterday for a one-day-old timestamp`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals("Yesterday", DetailFormatting.relativeAddedClause("2026-08-28T23:00:00Z", now))
    }

    @Test
    fun `relativeAddedClause counts days for anything under a month old`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals("5 days ago", DetailFormatting.relativeAddedClause("2026-08-24T12:00:00Z", now))
    }

    @Test
    fun `relativeAddedClause falls back to a bare date once it's more than a month old`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals("Jul 1, 2026", DetailFormatting.relativeAddedClause("2026-07-01T12:00:00Z", now))
    }

    @Test
    fun `relativeAddedClause is null for an absent or unparseable timestamp`() {
        assertNull(DetailFormatting.relativeAddedClause(null))
        assertNull(DetailFormatting.relativeAddedClause("not a date"))
    }

    @Test
    fun `movieEyebrow joins the verbatim library name with the uppercased added clause`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals("My Movies · ADDED 2 DAYS AGO", DetailFormatting.movieEyebrow("My Movies", "2026-08-27T12:00:00Z", now))
    }

    @Test
    fun `movieEyebrow never alters the library name's own casing`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals("my movies · ADDED TODAY", DetailFormatting.movieEyebrow("my movies", "2026-08-29T00:00:00Z", now))
    }

    @Test
    fun `movieEyebrow drops a missing added clause`() {
        assertEquals("My Movies", DetailFormatting.movieEyebrow("My Movies", null))
    }

    @Test
    fun `movieEyebrow drops a missing library name`() {
        val now = Instant.parse("2026-08-29T12:00:00Z")
        assertEquals("ADDED TODAY", DetailFormatting.movieEyebrow(null, "2026-08-29T00:00:00Z", now))
    }

    @Test
    fun `movieEyebrow is null when both halves are absent`() {
        assertNull(DetailFormatting.movieEyebrow(null, null))
    }

    // -- peopleLine / studioSummary ----------------------------------------

    @Test
    fun `peopleLine joins names verbatim, comma-separated`() {
        assertEquals("Dana Director, Pat Producer", DetailFormatting.peopleLine(listOf("Dana Director", "Pat Producer")))
    }

    @Test
    fun `peopleLine is null for an empty list`() {
        assertNull(DetailFormatting.peopleLine(emptyList()))
    }

    @Test
    fun `studioSummary is the bare name for a single studio`() {
        assertEquals("Studio One", DetailFormatting.studioSummary(listOf("Studio One")))
    }

    @Test
    fun `studioSummary appends a plus-N-more count for additional studios`() {
        assertEquals("Studio One +2 more", DetailFormatting.studioSummary(listOf("Studio One", "Studio Two", "Studio Three")))
    }

    @Test
    fun `studioSummary is null for an empty list`() {
        assertNull(DetailFormatting.studioSummary(emptyList()))
    }

    @Test
    fun `resolveFocusSeedTarget prefers the primary target when both are available`() {
        assertEquals(DetailFormatting.FocusSeedTarget.PRIMARY, DetailFormatting.resolveFocusSeedTarget(hasPrimary = true, hasSecondary = true))
    }

    @Test
    fun `resolveFocusSeedTarget prefers the primary target when only it is available`() {
        assertEquals(DetailFormatting.FocusSeedTarget.PRIMARY, DetailFormatting.resolveFocusSeedTarget(hasPrimary = true, hasSecondary = false))
    }

    @Test
    fun `resolveFocusSeedTarget falls back to the secondary target when there is no primary`() {
        assertEquals(DetailFormatting.FocusSeedTarget.SECONDARY, DetailFormatting.resolveFocusSeedTarget(hasPrimary = false, hasSecondary = true))
    }

    @Test
    fun `resolveFocusSeedTarget is NONE when neither target is available`() {
        assertEquals(DetailFormatting.FocusSeedTarget.NONE, DetailFormatting.resolveFocusSeedTarget(hasPrimary = false, hasSecondary = false))
    }

    // -- fittingItemCount (docs/19-detail-action-menu.md §1.5 FIX B/C) ------

    @Test
    fun `fittingItemCount keeps every item when they all fit`() {
        assertEquals(3, DetailFormatting.fittingItemCount(listOf(10f, 20f, 15f), separatorWidth = 5f, maxWidth = 100f))
    }

    @Test
    fun `fittingItemCount drops the last item once it no longer fits`() {
        // 10 + 5 + 20 = 35 fits in 40; +5 +15 = 55 doesn't.
        assertEquals(2, DetailFormatting.fittingItemCount(listOf(10f, 20f, 15f), separatorWidth = 5f, maxWidth = 40f))
    }

    @Test
    fun `fittingItemCount drops several trailing items`() {
        assertEquals(1, DetailFormatting.fittingItemCount(listOf(10f, 20f, 15f, 30f), separatorWidth = 5f, maxWidth = 12f))
    }

    @Test
    fun `fittingItemCount is 1 when even the first item alone is too wide`() {
        assertEquals(1, DetailFormatting.fittingItemCount(listOf(500f, 20f), separatorWidth = 5f, maxWidth = 100f))
    }

    @Test
    fun `fittingItemCount is 0 for an empty item list`() {
        assertEquals(0, DetailFormatting.fittingItemCount(emptyList(), separatorWidth = 5f, maxWidth = 100f))
    }

    @Test
    fun `fittingItemCount counts an exact-fit boundary as fitting`() {
        // 10 + 5 + 20 == 35 exactly.
        assertEquals(2, DetailFormatting.fittingItemCount(listOf(10f, 20f), separatorWidth = 5f, maxWidth = 35f))
    }

    // -- wholeCardCount (docs/19-detail-action-menu.md §1.5 FIX D) ----------

    @Test
    fun `wholeCardCount fits three episode cards in the panel-open content width`() {
        assertEquals(3, DetailFormatting.wholeCardCount(contentWidthDp = 630f, startMarginDp = 40f, cardWidthDp = 172f, gapDp = 15f))
    }

    @Test
    fun `wholeCardCount fits five cards at the full closed-panel screen width`() {
        assertEquals(5, DetailFormatting.wholeCardCount(contentWidthDp = 960f, startMarginDp = 40f, cardWidthDp = 172f, gapDp = 15f))
    }

    @Test
    fun `wholeCardCount fits four cards at an exact-fit content width`() {
        assertEquals(4, DetailFormatting.wholeCardCount(contentWidthDp = 773f, startMarginDp = 40f, cardWidthDp = 172f, gapDp = 15f))
    }

    @Test
    fun `wholeCardCount drops to three just under that exact-fit boundary`() {
        assertEquals(3, DetailFormatting.wholeCardCount(contentWidthDp = 772.9f, startMarginDp = 40f, cardWidthDp = 172f, gapDp = 15f))
    }

    @Test
    fun `wholeCardCount never returns fewer than one card, even at a tiny width`() {
        assertEquals(1, DetailFormatting.wholeCardCount(contentWidthDp = 0f, startMarginDp = 40f, cardWidthDp = 172f, gapDp = 15f))
    }
}
