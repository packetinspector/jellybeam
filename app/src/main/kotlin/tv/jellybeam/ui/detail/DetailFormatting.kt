package tv.jellybeam.ui.detail

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.common.countLabel
import uniffi.jellybeam_core.AudioSpatialKind
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.MediaStreamInfo
import uniffi.jellybeam_core.MediaStreamKind
import uniffi.jellybeam_core.PersonInfo

/** Pure Kotlin, Detail-screen-only formatting/resolution rules for `docs/11-detail-ux-spec.md`,
 * free of any Compose/Android dependency, same discipline as [CardFormatting].
 */
object DetailFormatting {

    private const val SERIES_ITEM_TYPE = "Series"
    private const val MOVIE_ITEM_TYPE = "Movie"
    private const val EPISODE_ITEM_TYPE = "Episode"

    /** Single-file video kinds that aren't a Movie/Episode (`Video`, `MusicVideo`, `Recording`);
     * same resume semantics as a Movie.
     */
    private val OTHER_SINGLE_VIDEO_ITEM_TYPES = setOf("Video", "MusicVideo", "Recording")
    private const val SPECIALS_SEASON_NAME = "Specials"

    private const val LABEL_PLAY = "Play"
    private const val LABEL_RESUME = "Resume"

    /**
     * The detail header's meta-line separator -- Tier-2 item 6 calls for a wider gap than
     * [CardFormatting.detailMetaLine]'s `" · "`. Non-private (docs/19 §1.5): [ItemBoundaryLine]
     * needs this exact string to join whatever prefix of items fits.
     */
    const val META_SEPARATOR = "  ·  "

    /** Tier-2 item 6's Movie/Episode header meta line: `"{year}{sep}{runtime}"`, dropping whichever
     * half is missing rather than formatting a literal zero.
     */
    fun movieMetaLine(productionYear: Int?, runtimeTicks: Long?): String? {
        val yearPart = productionYear?.toString()
        val runtimePart = runtimeTicks?.let { CardFormatting.formatRuntime(it) }
        return joinMetaParts(yearPart, runtimePart)
    }

    /**
     * docs/11 item 10's year-range rule: `"{year}–{endYear}"` once a Series has finished airing
     * with a known end date, `"{year}–"` while `Continuing`, else the bare year -- never a
     * dangling dash for a series that ended with no recorded `EndDate`. `null` with no
     * [productionYear]. [endYear]/[status] default `null` so a caller without [ItemDetail] loaded
     * yet degrades to the bare-year case rather than assuming either state.
     */
    fun yearRangeLabel(productionYear: Int?, endYear: Int? = null, status: String? = null): String? {
        val year = productionYear ?: return null
        return when {
            endYear != null -> "$year–$endYear"
            status == STATUS_CONTINUING -> "$year–"
            else -> "$year"
        }
    }

    private const val STATUS_CONTINUING = "Continuing"

    /**
     * Spec item 10's Series header meta line: `"{yearRange} · {n} season(s)"` -- never runtime or
     * genres, never a placeholder "0 seasons" while loading. `seasonCount` must come from the
     * loaded seasons list, never a server `ChildCount` field.
     */
    fun seriesMetaLine(productionYear: Int?, seasonCount: Int, endYear: Int? = null, status: String? = null): String? {
        val yearPart = yearRangeLabel(productionYear, endYear, status)
        val seasonPart = if (seasonCount > 0) "$seasonCount season${if (seasonCount == 1) "" else "s"}" else null
        return joinMetaParts(yearPart, seasonPart)
    }

    private fun joinMetaParts(first: String?, second: String?): String? = when {
        first != null && second != null -> "$first$META_SEPARATOR$second"
        first != null -> first
        second != null -> second
        else -> null
    }

    /**
     * Spec item 6's hero poster fallback chain: own PRIMARY, else the series' PRIMARY. `null` for
     * an Episode -- the poster slot is omitted entirely rather than stretching a landscape still
     * into a 2:3 box.
     */
    fun heroPosterArtSource(card: Card): ArtSource? {
        if (card.itemType == EPISODE_ITEM_TYPE) return null
        card.primaryTag?.let { return ArtSource.Own(card.id, it, ImageKind.PRIMARY) }
        val seriesId = card.seriesId
        val seriesTag = card.seriesPrimaryTag
        return if (seriesId != null && seriesTag != null) {
            ArtSource.Fallback(seriesId, seriesTag, ImageKind.PRIMARY)
        } else {
            ArtSource.None
        }
    }

    /** What the Detail screen's primary action pill should show, and what it does when pressed. */
    sealed interface PrimaryAction {
        /** [targetId]: the card itself for Movie/Episode, the resolved episode's id for a Series.
         * [hasProgress] gates the "Start from beginning" secondary pill (spec item 11).
         */
        data class Playable(val label: String, val targetId: String, val hasProgress: Boolean) : PrimaryAction

        /** Virtual item -- not yet on disk; [label] is the airing/missing status standing in for
         * "Play".
         */
        data class Unavailable(val label: String) : PrimaryAction

        /** Nothing resolvable to play: an unknown item type, or a Series with no usable episode
         * loaded yet.
         */
        data object None : PrimaryAction
    }

    /**
     * Spec item 11's resume semantics, a pure function (JVM-testable, no ViewModel/Compose).
     *
     * Movie/Episode (and [OTHER_SINGLE_VIDEO_ITEM_TYPES]): virtual -> [PrimaryAction.Unavailable]
     * with the virtual-status label; else "Resume" once `positionTicks > 0`, else "Play".
     *
     * Series: resolved against [seriesEpisodes] (all episodes across every season, not just the
     * selected tab). Specials (`parentIndexNumber == 0`) sort last, virtuals are skipped. First
     * episode with progress wins: `"Resume S{n} E{n}"`, degrading to plain `"Resume"` when either
     * number is missing; otherwise the first unplayed episode wins plain `"Play"`; otherwise
     * [PrimaryAction.None] -- spec doesn't define a "rewatch" fallback when everything's watched.
     */
    fun resolvePrimaryAction(card: Card, seriesEpisodes: List<Card>, now: Instant = Instant.now()): PrimaryAction =
        when (card.itemType) {
            MOVIE_ITEM_TYPE, EPISODE_ITEM_TYPE -> resolveSingleItemAction(card, now)
            in OTHER_SINGLE_VIDEO_ITEM_TYPES -> resolveSingleItemAction(card, now)
            SERIES_ITEM_TYPE -> resolveSeriesAction(seriesEpisodes)
            else -> PrimaryAction.None
        }

    private fun resolveSingleItemAction(card: Card, now: Instant): PrimaryAction {
        if (card.isVirtual) return PrimaryAction.Unavailable(CardFormatting.virtualStatusLabel(card.premiereDate, now))
        val resuming = card.positionTicks > 0
        return PrimaryAction.Playable(
            label = if (resuming) LABEL_RESUME else LABEL_PLAY,
            targetId = card.id,
            hasProgress = resuming,
        )
    }

    /** Shared "which episode would a viewer hit next" ordering ([resolveSeriesAction],
     * [resolveResumeSeason]): non-virtual, Specials pushed last via a stable sort.
     */
    private fun orderedPlayableEpisodes(seriesEpisodes: List<Card>): List<Card> =
        seriesEpisodes.filterNot { it.isVirtual }.sortedBy { if (isSpecialEpisode(it)) 1 else 0 }

    private fun resolveSeriesAction(seriesEpisodes: List<Card>): PrimaryAction {
        val candidates = orderedPlayableEpisodes(seriesEpisodes)
        val resuming = candidates.firstOrNull { it.positionTicks > 0 }
        if (resuming != null) {
            val seasonEpisode = strictSeasonEpisode(resuming.parentIndexNumber, resuming.indexNumber)
            val label = if (seasonEpisode != null) "$LABEL_RESUME $seasonEpisode" else LABEL_RESUME
            return PrimaryAction.Playable(label, resuming.id, hasProgress = true)
        }
        val next = candidates.firstOrNull { !it.played }
        if (next != null) return PrimaryAction.Playable(LABEL_PLAY, next.id, hasProgress = false)
        return PrimaryAction.None
    }

    /** `"S{n} E{n}"`, but unlike [CardFormatting.seasonEpisodeLabel] degrades to `null` the moment
     * either half is missing -- spec item 11 bans a partial "S2"/"E6" here.
     */
    private fun strictSeasonEpisode(parentIndexNumber: Int?, indexNumber: Int?): String? =
        if (parentIndexNumber != null && indexNumber != null) "S$parentIndexNumber E$indexNumber" else null

    private fun isSpecialEpisode(episode: Card): Boolean = episode.parentIndexNumber == 0

    /**
     * The pure "which target should this screen's initial focus seed land on" decision: primary
     * wins, secondary only as a fallback. [FocusSeedTarget.NONE] means nothing is focusable yet
     * (both inputs false) -- the caller's seed effect must not claim its "seeded once" latch in
     * that case, or a still-loading screen can get permanently stuck with a dead remote once its
     * data does arrive, since nothing is left to retry the seed.
     */
    enum class FocusSeedTarget { PRIMARY, SECONDARY, NONE }

    fun resolveFocusSeedTarget(hasPrimary: Boolean, hasSecondary: Boolean): FocusSeedTarget = when {
        hasPrimary -> FocusSeedTarget.PRIMARY
        hasSecondary -> FocusSeedTarget.SECONDARY
        else -> FocusSeedTarget.NONE
    }

    private fun isSpecialSeason(season: Card): Boolean = season.name == SPECIALS_SEASON_NAME || season.indexNumber == 0

    /** Spec item 3: prefers the first season that's neither named "Specials" nor indexed 0, falling
     * back to the literal first season only if every one fails that check.
     */
    fun defaultSeason(seasons: List<Card>): Card? = seasons.firstOrNull { !isSpecialSeason(it) } ?: seasons.firstOrNull()

    /** Season tab strip order: Specials sorted last (stable sort). Shares [defaultSeason]'s "what
     * counts as special" rule so the tab D-pad focus lands on first always matches.
     */
    fun seasonsWithSpecialsLast(seasons: List<Card>): List<Card> = seasons.sortedBy { if (isSpecialSeason(it)) 1 else 0 }

    /**
     * Series page's initial season-chip preselection. Mirrors [resolveSeriesAction]'s priority so
     * the chip always matches whichever episode the primary action button would play: (a) an
     * in-progress episode's season wins outright; (b) else the first unplayed episode's season;
     * (c) else falls back to [defaultSeason] (a fully-watched series has no spec'd "rewatch"
     * target, so landing on season 1 is deliberate). [DetailViewModel] calls this exactly once per
     * page visit, only after both seasons have loaded and `seriesEpisodes` has settled
     * (docs/15-focus-and-selection.md §0.2/§5) -- an empty [seriesEpisodes] (no episodes, or a
     * failed fetch) degrades to the per-season [Card.unplayedCount] fallback either way.
     */
    fun resolveResumeSeason(seasons: List<Card>, seriesEpisodes: List<Card>): Card? {
        if (seasons.isEmpty()) return null
        if (seriesEpisodes.isNotEmpty()) {
            val candidates = orderedPlayableEpisodes(seriesEpisodes)
            val target = candidates.firstOrNull { it.positionTicks > 0 } ?: candidates.firstOrNull { !it.played }
            val targetSeason = target?.let { episode -> seasons.firstOrNull { it.indexNumber == episode.parentIndexNumber } }
            if (targetSeason != null) return targetSeason
            // Fully watched, or a season/episode data mismatch: documented season-1 fallback.
            return defaultSeason(seasons)
        }
        // seriesEpisodes settled empty (no episodes, or the fetch failed): per-season unplayedCount
        // is the best signal available.
        val bySeasonCount = seasons.firstOrNull { !isSpecialSeason(it) && it.unplayedCount != 0L }
        return bySeasonCount ?: defaultSeason(seasons)
    }

    // -- Spec strip (docs/11 tier 1 item 1) -------------------------------

    /** docs/11 item 1's three DATA-DRIVEN emphasis tiers -- see [classify]. Never hard-coded per
     * call site.
     */
    enum class SpecWeight { BASELINE, NOTABLE, BEST_IN_CLASS }

    /** One pill cell: a value string (already display-ready, e.g. `"1080P"`, `"HEVC"`) plus its
     * [classify]-derived tier.
     */
    data class SpecField(val value: String, val weight: SpecWeight)

    private val BEST_IN_CLASS_TERMS = listOf("DOLBY VISION", "ATMOS", "DTS:X", "DTS-HD", "TRUEHD", "DTSHD")
    private val NOTABLE_TERMS = listOf("4K", "8K", "2160P", "4320P", "HDR", "HLG", "HEVC", "AV1", "DIRECT PLAY")
    private val LOSSLESS_WORDS = setOf("FLAC", "PCM", "ALAC")
    private val WORD_SPLIT = Regex("[^A-Z0-9]+")

    /** Ranked strongest-first so a value qualifying for two tiers lands on the higher one. Expects
     * an already-uppercased value.
     */
    fun classify(value: String): SpecWeight {
        if (BEST_IN_CLASS_TERMS.any { value.contains(it) }) return SpecWeight.BEST_IN_CLASS
        val bitDepthDigits = value.removeSuffix("-BIT")
        if (bitDepthDigits != value && (bitDepthDigits.toIntOrNull() ?: -1) >= 10) return SpecWeight.BEST_IN_CLASS
        if (NOTABLE_TERMS.any { value.contains(it) }) return SpecWeight.NOTABLE
        if (value.contains("LOSSLESS")) return SpecWeight.NOTABLE
        if (value.split(WORD_SPLIT).any { it in LOSSLESS_WORDS }) return SpecWeight.NOTABLE
        return SpecWeight.BASELINE
    }

    /** Resolution ladder that promotes on either axis (matching jellyfin-web) so a widescreen crop
     * doesn't get demoted the way a height-first ladder would.
     */
    fun resolutionLabel(width: Int?, height: Int?): String? {
        val h = height?.takeIf { it > 0 } ?: return null
        val w = width ?: 0
        return when {
            w >= 7000 || h >= 4000 -> "8K"
            w >= 3500 || h >= 2000 -> "4K"
            w >= 2400 || h >= 1400 -> "1440P"
            w >= 1800 || h >= 1000 -> "1080P"
            w >= 1200 || h >= 700 -> "720P"
            w >= 1000 || h >= 560 -> "576P"
            else -> "SD"
        }
    }

    /** Every Dolby Vision `videoRangeType` variant collapses to `"DOLBY VISION"`; falls back to the
     * coarse `videoRange`'s "HDR"/"SDR" split otherwise.
     */
    private val DOLBY_VISION_TYPES = setOf(
        "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithEL", "DOVIWithHDR10Plus", "DOVIWithELHDR10Plus",
    )

    fun hdrLabel(videoRange: String?, videoRangeType: String?): String? {
        val fromType = when {
            videoRangeType == "HDR10" -> "HDR10"
            videoRangeType == "HDR10Plus" -> "HDR10+"
            videoRangeType == "HLG" -> "HLG"
            videoRangeType in DOLBY_VISION_TYPES -> "DOLBY VISION"
            else -> null
        }
        return fromType ?: if (videoRange == "HDR") "HDR" else null
    }

    /**
     * The server's raw channel count only: [MediaStreamInfo] carries no `ChannelLayout` string, so
     * a mix that isn't exactly 6/8 channels falls to `"N CH"`. TODO: named layouts need
     * `ChannelLayout` added to the FFI's `MediaStreamInfo`.
     */
    fun channelLabel(channels: Int?): String? = when (channels) {
        1 -> "MONO"
        2 -> "STEREO"
        6 -> "5.1"
        8 -> "7.1"
        else -> channels?.takeIf { it > 0 }?.let { "$it CH" }
    }

    /**
     * `"AAC 5.1"`, `"TRUEHD 7.1 ATMOS"` -- codec with the channel layout appended, plus the
     * object-based format when the core found one (never repeated if the codec already names it).
     */
    fun audioLabel(codec: String?, channels: Int?, spatial: AudioSpatialKind? = null): String? {
        val name = codec?.takeIf { it.isNotBlank() }?.uppercase() ?: return null
        val suffix = when (spatial) {
            AudioSpatialKind.DOLBY_ATMOS -> "ATMOS"
            AudioSpatialKind.DTS_X -> "DTS:X"
            null -> null
        }?.takeUnless { name.contains(it) }
        return listOfNotNull(name, channelLabel(channels), suffix).joinToString(" ")
    }

    private fun formatMbps(bitRate: Int): String = String.format(Locale.US, "%.1f MBPS", bitRate / 1_000_000.0)

    /**
     * docs/11 item 1's field order: RESOLUTION │ VIDEO CODEC │ BIT DEPTH │ HDR │ AUDIO(+ch) │
     * BITRATE │ CONTAINER. SIZE is omitted: [ItemDetail] carries no file-size field (TODO: needs
     * `MediaSourceInfo.size`). BITRATE reads the video stream's own bit rate as the closest
     * available stand-in for an overall source bitrate. Unknown fields are omitted, never
     * placeholdered.
     */
    fun specStripFields(detail: ItemDetail): List<SpecField> {
        val video = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.VIDEO }
        val audio = detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO && it.isDefault }
            ?: detail.mediaStreams.firstOrNull { it.streamType == MediaStreamKind.AUDIO }
        val out = mutableListOf<SpecField>()
        resolutionLabel(video?.width, video?.height)?.let { out += SpecField(it, classify(it)) }
        video?.codec?.takeIf { it.isNotBlank() }?.uppercase()?.let { out += SpecField(it, classify(it)) }
        video?.bitDepth?.let {
            val value = "$it-BIT"
            out += SpecField(value, classify(value))
        }
        hdrLabel(video?.videoRange, video?.videoRangeType)?.let { out += SpecField(it, classify(it)) }
        audioLabel(audio?.codec, audio?.channels, audio?.audioSpatial)?.let { out += SpecField(it, classify(it)) }
        video?.bitRate?.takeIf { it > 0 }?.let {
            val value = formatMbps(it)
            out += SpecField(value, classify(value))
        }
        containerLabel(detail.container)?.let { out += SpecField(it, classify(it)) }
        return out
    }

    // docs/11 tier 2 item 3: details footer.

    private const val DETAILS_SEPARATOR = " │ "

    /**
     * The footer's field order (genres, studios, official rating, year range): a full factual
     * listing below the overview, distinct from and additive to [movieMetaLine]/[seriesMetaLine]'s
     * compact header. Genres are dropped for a Series (docs/11 item 10); every other field is
     * simply omitted when empty/blank, never placeholdered.
     */
    fun detailsFooterFields(
        itemType: String,
        genres: List<String>,
        studios: List<String>,
        officialRating: String?,
        productionYear: Int?,
        endYear: Int?,
        status: String?,
    ): List<String> {
        val genrePart = if (itemType != SERIES_ITEM_TYPE) {
            genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        } else {
            null
        }
        val studioPart = studios.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val ratingPart = officialRating?.takeIf { it.isNotBlank() }
        val yearPart = if (itemType == SERIES_ITEM_TYPE) {
            yearRangeLabel(productionYear, endYear, status)
        } else {
            productionYear?.toString()
        }
        return listOfNotNull(genrePart, studioPart, ratingPart, yearPart)
    }

    /** [detailsFooterFields], joined into the one wrapping Martian Mono line docs/11 item 8
     * describes -- `null` when every field is absent.
     */
    fun detailsFooterLine(
        itemType: String,
        genres: List<String>,
        studios: List<String>,
        officialRating: String?,
        productionYear: Int?,
        endYear: Int?,
        status: String?,
    ): String? {
        val fields = detailsFooterFields(itemType, genres, studios, officialRating, productionYear, endYear, status)
        return fields.takeIf { it.isNotEmpty() }?.joinToString(DETAILS_SEPARATOR)?.uppercase(Locale.US)
    }

    // docs/11 tier 2 item 9: cast row.

    /**
     * Item 9: "skip anyone without a portrait -- no gray placeholder circles." Both an id and a
     * primary-image tag are required, the same "resolves to a real image or is dropped" gate
     * of its own -- a cast member has no ancestor image to fall back to.
     */
    fun castMembers(people: List<PersonInfo>): List<PersonInfo> = people.filter { it.id != null && it.primaryImageTag != null }

    /**
     * `LazyColumn/Row`/`LazyVerticalGrid` throw when two items in the same container share a key.
     * For a [Card] list, a repeated id is a data error, not something legitimate (unlike people --
     * see [castMembers]'s call site, which widens its key instead). Keeps the first occurrence,
     * same semantics as [kotlin.collections.distinctBy].
     */
    fun <T> dedupeById(items: List<T>, id: (T) -> String): List<T> = items.distinctBy(id)

    /**
     * The server's `Container` field is a comma-joined list of every extension the file could be
     * served as; a file has exactly one actual container, so this shows only the first candidate.
     */
    fun containerLabel(container: String?): String? {
        val trimmed = container?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.substringBefore(',').trim().takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
    }

    /**
     * Builds a synthetic Series [Card] from an Episode's own fields so the "View Series" pill can
     * reuse existing `onOpenDetail(Card)` plumbing with no new FFI: every Episode row already
     * carries `seriesId`/`seriesName`/`seriesPrimaryTag`, so this is a plain field remap.
     * [seriesPrimaryTag] is threaded into the synthetic card's own `primaryTag` slot so
     * [heroPosterArtSource] resolves the series' real poster immediately, no flash of missing art.
     * `null` when the episode carries no `seriesId` -- callers must hide the pill entirely.
     */
    // These two pure predicates are [DetailScreen]'s reveal gates, pulled out here so they're
    // JVM-testable: the page used to reveal each below-fold section the instant its own
    // independent fetch landed, several arrivals spread over time each shoving content down a beat.

    /**
     * Whether the itemDetail-driven trio (spec strip, details footer, cast row) should reveal (or
     * collapse) as one batch. A Series' spec strip never has anything to show in any itemDetail
     * state, so it alone is ready immediately rather than waiting on the real fetch.
     */
    fun specStripReady(itemType: String, itemDetailLoaded: Boolean): Boolean =
        itemType == SERIES_ITEM_TYPE || itemDetailLoaded

    /**
     * Whether the Series episode grid should show skeleton cells: true while either the season
     * list or the selected season's episode list is loading, covering both the first load and a
     * later season-tab switch with the same rule. Always `false` for a non-Series item type.
     */
    fun showEpisodeSkeleton(isSeries: Boolean, isLoadingSeasons: Boolean, isLoadingEpisodes: Boolean): Boolean =
        isSeries && (isLoadingSeasons || isLoadingEpisodes)

    /**
     * §D.1 fix: [parentBackdropItemId]/[parentBackdropTag] are carried over
     * from the episode rather than nulled out. Jellyfin resolves an
     * episode's `ParentBackdropItemId`/`ParentBackdropImageTags` by walking
     * up to the nearest ancestor that actually has backdrop art -- for a
     * typical library (no per-season backdrop art) that's the Series
     * itself -- so these two fields are usually already exactly the
     * synthetic card's OWN backdrop, and [CardFormatting.backdropArtSource]
     * falls back to them the moment [backdropTag] itself is `null` (true
     * here: an Episode's own [Card] never carries its series' `backdrop_tag`
     * column directly, only this parent-pointer pair). Previously nulling
     * both meant the View-Series synthetic card ALWAYS rendered a flat,
     * art-less backdrop panel; this is the one concrete in-fence fix for
     * this pass's "Series backdrop renders black" report -- see this
     * file's own report for what's NOT fixed here (a Series card reached
     * the normal way, straight from the mirror, was already carrying its
     * real `backdropTag` and isn't touched by this function at all).
     */
    fun seriesCardFrom(episode: Card): Card? {
        val seriesId = episode.seriesId ?: return null
        return Card(
            id = seriesId,
            itemType = SERIES_ITEM_TYPE,
            name = episode.seriesName.orEmpty(),
            primaryTag = episode.seriesPrimaryTag,
            backdropTag = null,
            thumbTag = null,
            blurhash = null,
            played = false,
            positionTicks = 0,
            runtimeTicks = null,
            unplayedCount = null,
            productionYear = null,
            indexNumber = null,
            premiereDate = null,
            parentIndexNumber = null,
            seriesId = seriesId,
            seriesPrimaryTag = episode.seriesPrimaryTag,
            parentBackdropItemId = episode.parentBackdropItemId,
            parentBackdropTag = episode.parentBackdropTag,
            seriesName = episode.seriesName,
            lastPlayedDate = null,
            overview = null,
            isVirtual = false,
            libraryId = episode.libraryId,
        )
    }

    // Pure formatters backing the Episode/Series/Movie detail screens (see DetailScreen.kt's
    // per-screen composables).

    /** A tighter separator than [META_SEPARATOR]: micro card-level text rather than a hero's header
     * meta line.
     */
    private const val THIN_SEPARATOR = " · "

    /** Generalized [joinMetaParts]: drops whichever half is missing, joins with [separator] when
     * both are present.
     */
    private fun join(first: String?, second: String?, separator: String): String? = when {
        first != null && second != null -> "$first$separator$second"
        first != null -> first
        second != null -> second
        else -> null
    }

    private val MEDIUM_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** `"Mar 6, 2014"` from an RFC3339 timestamp, `null` if absent/unparseable. Shared by
     * [runtimeAndDateLine] and [relativeAddedClause]'s "older than a month" fallback.
     */
    fun shortDate(dateStr: String?): String? =
        dateStr?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }?.let { MEDIUM_DATE_FORMAT.format(it) }

    /** §3 item 7 / §2 item 5's episode-grid-cell/Up-Next-panel meta line: `"{runtime} · {date}"`,
     * dropping whichever half is missing.
     */
    fun runtimeAndDateLine(runtimeTicks: Long?, premiereDate: String?): String? {
        val runtimePart = runtimeTicks?.let { CardFormatting.formatRuntime(it) }
        val datePart = shortDate(premiereDate)
        return join(runtimePart, datePart, THIN_SEPARATOR)
    }

    /** §2 item 2's Episode eyebrow: `"{seriesName} · S{p} E{i}"`, dropping whichever half is
     * missing (reuses [CardFormatting.seasonEpisodeLabel]'s "never S? E?" rule).
     */
    fun episodeEyebrow(seriesName: String?, parentIndexNumber: Int?, indexNumber: Int?): String? {
        val namePart = seriesName?.takeIf { it.isNotBlank() }
        val sePart = CardFormatting.seasonEpisodeLabel(parentIndexNumber, indexNumber)
        return join(namePart, sePart, THIN_SEPARATOR)
    }

    /** §2 item 2's Episode header line as constituent parts -- docs/19 §1.5's [ItemBoundaryLine]
     * needs individual items to drop trailing ones whole.
     */
    fun episodeHeaderItems(productionYear: Int?, runtimeTicks: Long?, officialRating: String?, genres: List<String>): List<String> {
        val yearPart = productionYear?.toString()
        val runtimePart = runtimeTicks?.let { CardFormatting.formatRuntime(it) }
        val ratingPart = officialRating?.takeIf { it.isNotBlank() }
        val genrePart = genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return listOfNotNull(yearPart, runtimePart, ratingPart, genrePart)
    }

    /** §2 item 2's Episode header line: `"{year} · {runtime} · {rating} · {genres}"`, each part
     * dropped when absent. Genres uncapped. Delegates to [episodeHeaderItems].
     */
    fun episodeHeaderLine(productionYear: Int?, runtimeTicks: Long?, officialRating: String?, genres: List<String>): String? =
        episodeHeaderItems(productionYear, runtimeTicks, officialRating, genres).takeIf { it.isNotEmpty() }?.joinToString(META_SEPARATOR)

    /**
     * §3 item 4's Series header line: `"{yearRange} · {n} seasons · {n} episodes · {rating} ·
     * {genres}"`. Season count from [ItemDetail.childCount], episode count from
     * [ItemDetail.recursiveItemCount] -- both `null` until the fetch resolves, omitting those
     * clauses rather than placeholdering. Genres capped at 2.
     */
    fun seriesHeaderItems(
        productionYear: Int?,
        endYear: Int?,
        status: String?,
        seasonCount: Int?,
        episodeCount: Int?,
        officialRating: String?,
        genres: List<String>,
    ): List<String> {
        val yearPart = yearRangeLabel(productionYear, endYear, status)
        val seasonPart = seasonCount?.takeIf { it > 0 }?.let { "$it season${if (it == 1) "" else "s"}" }
        val episodePart = episodeCount?.takeIf { it > 0 }?.let { "$it episode${if (it == 1) "" else "s"}" }
        val ratingPart = officialRating?.takeIf { it.isNotBlank() }
        val genrePart = genres.filter { it.isNotBlank() }.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ")
        return listOfNotNull(yearPart, seasonPart, episodePart, ratingPart, genrePart)
    }

    /** §3 item 4's Series header line -- see [seriesHeaderItems] for the parts [ItemBoundaryLine]
     * needs; this joins all of them verbatim.
     */
    fun seriesHeaderLine(
        productionYear: Int?,
        endYear: Int?,
        status: String?,
        seasonCount: Int?,
        episodeCount: Int?,
        officialRating: String?,
        genres: List<String>,
    ): String? =
        seriesHeaderItems(productionYear, endYear, status, seasonCount, episodeCount, officialRating, genres)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(META_SEPARATOR)

    /**
     * §3 item 6's season-selector summary: `"{SEASON NAME} · {n} EPISODES · {n} WATCHED"`, computed
     * from the loaded episode list, never a server field. [seasonName] is uppercased for this one
     * badge's display register only -- the season chip itself still shows the server's name
     * verbatim.
     */
    fun seasonSummaryLine(seasonName: String, episodes: List<Card>): String =
        seasonSummaryItems(seasonName, episodes).joinToString(THIN_SEPARATOR)

    /**
     * [seasonSummaryLine]'s three parts, unjoined -- docs/19 §1.5's [ItemBoundaryLine] drops them
     * end-first as the panel reflow narrows. Unlike every other `*Items` variant, this one is never
     * empty -- always exactly `[name, count, watched]`.
     */
    fun seasonSummaryItems(seasonName: String, episodes: List<Card>): List<String> {
        val watched = episodes.count { it.played }
        return listOf(seasonName.uppercase(Locale.US), countLabel(episodes.size, "EPISODE", "EPISODES"), "$watched WATCHED")
    }

    /** §4 item 7's file-size cell: `"7.2 GB"` / `"512 MB"` -- GB at one decimal once >= 1024MB,
     * else whole-number MB. Binary (1024-based) units. `null` for absent/non-positive.
     */
    fun formatFileSize(bytes: Long?): String? {
        val value = bytes?.takeIf { it > 0 } ?: return null
        val megabytes = value / (1024.0 * 1024.0)
        return if (megabytes >= 1024.0) {
            String.format(Locale.US, "%.1f GB", megabytes / 1024.0)
        } else {
            "${megabytes.roundToLong()} MB"
        }
    }

    /**
     * ADDED row's relative clause: `"Today"` / `"Yesterday"` / `"N days ago"` (2-30 days), else
     * the bare [shortDate] once more than a month old. `null` when unparseable. Day boundaries use
     * each timestamp's own UTC-normalized date, not the system zone, keeping this deterministic.
     */
    fun relativeAddedClause(dateCreated: String?, now: Instant = Instant.now()): String? {
        val added = dateCreated?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
        val addedDate = added.toLocalDate()
        val today = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).toLocalDate()
        val days = ChronoUnit.DAYS.between(addedDate, today)
        return when {
            days <= 0L -> "Today"
            days == 1L -> "Yesterday"
            days <= 30L -> "$days days ago"
            else -> MEDIUM_DATE_FORMAT.format(added)
        }
    }

    /** §4 item 3's Movie eyebrow: `"{libraryName} · ADDED {clause}"`, uppercasing only the
     * generated clause -- [libraryName] stays exactly as given (never prettified).
     */
    fun movieEyebrow(libraryName: String?, dateCreated: String?, now: Instant = Instant.now()): String? {
        val libraryPart = libraryName?.takeIf { it.isNotBlank() }
        val addedPart = relativeAddedClause(dateCreated, now)?.let { "ADDED ${it.uppercase(Locale.US)}" }
        return join(libraryPart, addedPart, THIN_SEPARATOR)
    }

    /** §D.5: past this many seasons, the "SEASONS" label drops so the chip row keeps its one-row
     * layout instead of wrapping. A season-count heuristic, not a measured fit check.
     */
    private const val MANY_SEASONS_LABEL_DROP_THRESHOLD = 10

    fun showSeasonsLabel(seasonCount: Int): Boolean = seasonCount <= MANY_SEASONS_LABEL_DROP_THRESHOLD

    /** §4 item 6's DIRECTOR/WRITER detail-panel rows: server names verbatim, comma-joined; `null`
     * when empty.
     */
    fun peopleLine(names: List<String>): String? =
        names.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")

    /** §4 item 6's STUDIO row: the first studio's name plus a "+N more" count -- never the whole
     * list verbatim (the panel's single-line budget can't fit it).
     */
    fun studioSummary(studios: List<String>): String? {
        val clean = studios.filter { it.isNotBlank() }
        if (clean.isEmpty()) return null
        val extra = clean.size - 1
        return if (extra > 0) "${clean.first()} +$extra more" else clean.first()
    }

    // docs/19 §1.5 panel-open reflow: pure arithmetic backing [ItemBoundaryLine] and DetailScreen's
    // per-frame LazyRow card-window sizing.

    /**
     * The longest prefix (by count) of an items list, joined by a separator of known width, that
     * fits within [maxWidth], never splitting an item's text. Always at least 1 once [itemWidths]
     * is non-empty (the caller ellipsizes that lone item rather than this returning 0); 0 for an
     * empty list. Boundary is inclusive.
     */
    fun fittingItemCount(itemWidths: List<Float>, separatorWidth: Float, maxWidth: Float): Int {
        if (itemWidths.isEmpty()) return 0
        var total = itemWidths[0]
        var count = 1
        for (index in 1 until itemWidths.size) {
            val next = total + separatorWidth + itemWidths[index]
            if (next > maxWidth) break
            total = next
            count++
        }
        return count
    }

    /** How many [cardWidthDp]-wide cards (separated by [gapDp]) fit a [contentWidthDp]-wide row
     * starting [startMarginDp] in. Always at least 1.
     */
    fun wholeCardCount(contentWidthDp: Float, startMarginDp: Float, cardWidthDp: Float, gapDp: Float): Int {
        val available = contentWidthDp - startMarginDp + gapDp
        val perCard = cardWidthDp + gapDp
        return max(1, floor(available / perCard).toInt())
    }
}
