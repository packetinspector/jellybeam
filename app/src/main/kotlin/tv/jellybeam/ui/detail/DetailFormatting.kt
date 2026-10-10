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
import tv.jellybeam.R
import tv.jellybeam.i18n.AppLocale
import tv.jellybeam.i18n.mediumDateFormatter
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.CardFormatting
import uniffi.jellybeam_core.AudioSpatialKind
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ChangeEvent
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.MediaStreamInfo
import uniffi.jellybeam_core.MediaStreamKind
import uniffi.jellybeam_core.PersonInfo

/** Pure Kotlin, Detail-screen-only formatting/resolution rules for `docs/11-detail-ux-spec.md`,
 * free of any Compose/Android dependency, same discipline as [CardFormatting].
 */
object DetailFormatting {

    /** How a mirror change bears on a Series/BoxSet page; see [collectionChange]. */
    sealed interface CollectionChange {
        data object Affects : CollectionChange
        data object Ignores : CollectionChange

        /** Refresh only if the mirror says one of [unknownIds] is a child of the series. */
        data class AffectsIfChildOf(val unknownIds: List<String>) : CollectionChange
    }

    /**
     * Series/BoxSet change filter (docs/11 item 11). An unknown id refreshes a Series page only
     * once the mirror confirms it belongs there, so a new episode reaches the memory-served
     * season shelves without unrelated sync or userdata upserts re-querying the page.
     */
    fun collectionChange(
        event: ChangeEvent,
        cardId: String,
        isSeries: Boolean,
        knownIds: Set<String>,
    ): CollectionChange = when (event) {
        is ChangeEvent.Upserted -> when {
            knownIds.isEmpty() || event.ids.any { it == cardId || it in knownIds } -> CollectionChange.Affects
            !isSeries -> CollectionChange.Ignores
            else -> event.ids.filter { it !in knownIds && it != cardId }
                .takeIf { it.isNotEmpty() }
                ?.let { CollectionChange.AffectsIfChildOf(it) }
                ?: CollectionChange.Ignores
        }
        is ChangeEvent.Removed ->
            if (event.ids.any { it == cardId || it in knownIds }) CollectionChange.Affects else CollectionChange.Ignores
        ChangeEvent.Refresh -> CollectionChange.Affects
        ChangeEvent.ViewsChanged -> CollectionChange.Ignores
    }

    /** Seasons and episodes both carry their series id. */
    fun isChildOfSeries(cards: List<Card>, seriesId: String): Boolean = cards.any { it.seriesId == seriesId }

    private const val SERIES_ITEM_TYPE = "Series"
    private const val MOVIE_ITEM_TYPE = "Movie"
    private const val EPISODE_ITEM_TYPE = "Episode"

    /** Single-file video kinds that aren't a Movie/Episode (`Video`, `MusicVideo`, `Recording`);
     * same resume semantics as a Movie.
     */
    private val OTHER_SINGLE_VIDEO_ITEM_TYPES = setOf("Video", "MusicVideo", "Recording")

    /** Compared against server-provided season names, never displayed as our own text. */
    private const val SPECIALS_SEASON_NAME = "Specials"

    /**
     * The detail header's meta-line separator -- Tier-2 item 6 calls for a wider gap than
     * [CardFormatting.detailMetaLine]'s `" · "`. Non-private (docs/19 §1.5): [ItemBoundaryLine]
     * needs this exact string to join whatever prefix of items fits.
     */
    const val META_SEPARATOR = "  ·  "

    /** Tier-2 item 6's Movie/Episode header meta line: `"{year}{sep}{runtime}"`, dropping whichever
     * half is missing rather than formatting a literal zero.
     */
    fun movieMetaLine(strings: UiStrings, productionYear: Int?, runtimeTicks: Long?): String? {
        val yearPart = productionYear?.toString()
        val runtimePart = runtimeTicks?.let { CardFormatting.formatRuntime(strings, it) }
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
    fun seriesMetaLine(strings: UiStrings, productionYear: Int?, seasonCount: Int, endYear: Int? = null, status: String? = null): String? {
        val yearPart = yearRangeLabel(productionYear, endYear, status)
        val seasonPart = if (seasonCount > 0) strings.plural(R.plurals.detail_season_count, seasonCount, seasonCount) else null
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
    fun resolvePrimaryAction(strings: UiStrings, card: Card, seriesEpisodes: List<Card>, now: Instant = Instant.now()): PrimaryAction =
        when (card.itemType) {
            MOVIE_ITEM_TYPE, EPISODE_ITEM_TYPE -> resolveSingleItemAction(strings, card, now)
            in OTHER_SINGLE_VIDEO_ITEM_TYPES -> resolveSingleItemAction(strings, card, now)
            SERIES_ITEM_TYPE -> resolveSeriesAction(strings, seriesEpisodes)
            else -> PrimaryAction.None
        }

    private fun resolveSingleItemAction(strings: UiStrings, card: Card, now: Instant): PrimaryAction {
        if (card.isVirtual) return PrimaryAction.Unavailable(CardFormatting.virtualStatusLabel(strings, card.premiereDate, now))
        val resuming = card.positionTicks > 0
        return PrimaryAction.Playable(
            label = strings.get(if (resuming) R.string.detail_resume else R.string.detail_play),
            targetId = card.id,
            hasProgress = resuming,
        )
    }

    /** Shared "which episode would a viewer hit next" ordering ([resolveSeriesAction],
     * [resolveResumeSeason]): non-virtual, Specials pushed last via a stable sort.
     */
    private fun orderedPlayableEpisodes(seriesEpisodes: List<Card>): List<Card> =
        seriesEpisodes.filterNot { it.isVirtual }.sortedBy { if (isSpecialEpisode(it)) 1 else 0 }

    private fun resolveSeriesAction(strings: UiStrings, seriesEpisodes: List<Card>): PrimaryAction {
        val candidates = orderedPlayableEpisodes(seriesEpisodes)
        val resuming = candidates.firstOrNull { it.positionTicks > 0 }
        if (resuming != null) {
            val seasonEpisode = strictSeasonEpisode(strings, resuming.parentIndexNumber, resuming.indexNumber)
            val label = if (seasonEpisode != null) strings.get(R.string.detail_resume_episode, seasonEpisode) else strings.get(R.string.detail_resume)
            return PrimaryAction.Playable(label, resuming.id, hasProgress = true)
        }
        val next = candidates.firstOrNull { !it.played }
        if (next != null) return PrimaryAction.Playable(strings.get(R.string.detail_play), next.id, hasProgress = false)
        return PrimaryAction.None
    }

    /** `"S{n} E{n}"`, but unlike [CardFormatting.seasonEpisodeLabel] degrades to `null` the moment
     * either half is missing -- spec item 11 bans a partial "S2"/"E6" here.
     */
    private fun strictSeasonEpisode(strings: UiStrings, parentIndexNumber: Int?, indexNumber: Int?): String? =
        if (parentIndexNumber != null && indexNumber != null) {
            strings.get(R.string.detail_season_episode, parentIndexNumber, indexNumber)
        } else {
            null
        }

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

    /**
     * docs/15 §2 rule 3 for a series page: the primary pill wins whenever one exists, the selected
     * season chip only when the settled primary action is absent, the door last. `null` = not
     * decidable yet, so a fast-arriving chip row cannot pre-empt a Play/Resume still resolving.
     */
    fun resolveSeriesSeed(
        hasPrimary: Boolean,
        primarySettled: Boolean,
        hasChips: Boolean,
        seasonsSettled: Boolean,
    ): FocusSeedTarget? = when {
        hasPrimary -> FocusSeedTarget.PRIMARY
        !primarySettled -> null
        hasChips -> FocusSeedTarget.SECONDARY
        !seasonsSettled -> null
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
     * One season's episodes derived from the whole-series list, equal to the core's
     * `children(seasonId)`: match on `parentIndexNumber == season.indexNumber`, drop virtual
     * Episodes unless [showVirtualEpisodes], order by `indexNumber` (nulls last). `null` when memory
     * can't be faithful (no list, a season without a number, or an episode without a
     * `parentIndexNumber`, which the core resolves through `parent_id`, a field [Card] lacks).
     */
    fun episodesOfSeason(season: Card, allEpisodes: List<Card>, showVirtualEpisodes: Boolean): List<Card>? {
        val number = season.indexNumber ?: return null
        if (allEpisodes.isEmpty() || allEpisodes.any { it.parentIndexNumber == null }) return null
        return allEpisodes
            .filter { it.parentIndexNumber == number && (showVirtualEpisodes || !it.isVirtual) }
            .sortedWith(compareBy(nullsLast()) { it.indexNumber })
    }

    /**
     * Selection after the season list was replaced (docs/15 §5): kept while it is in [seasons] or
     * [seasons] is empty (an empty read also means mirror-unavailable, never "all removed"); a
     * removed pick, or none once [settled] with seasons present, falls to the resume season, else
     * the first. Before [settled] a null stays null, and a removed pick is also null, so the resume
     * pick is never provisional (docs/15 §5).
     */
    fun reconcileSelectedSeason(selectedId: String?, seasons: List<Card>, allEpisodes: List<Card>, settled: Boolean): String? {
        if (selectedId == null && !settled) return null
        if (selectedId != null && (seasons.isEmpty() || seasons.any { it.id == selectedId })) return selectedId
        if (!settled) return null
        return (resolveResumeSeason(seasons, allEpisodes) ?: seasons.firstOrNull())?.id
    }

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
    fun channelLabel(strings: UiStrings, channels: Int?): String? = when (channels) {
        1 -> strings.get(R.string.detail_channels_mono)
        2 -> strings.get(R.string.detail_channels_stereo)
        6 -> "5.1"
        8 -> "7.1"
        else -> channels?.takeIf { it > 0 }?.let { strings.get(R.string.detail_channels_count, it) }
    }

    /**
     * `"AAC 5.1"`, `"TRUEHD 7.1 ATMOS"` -- codec with the channel layout appended, plus the
     * object-based format when the core found one (never repeated if the codec already names it).
     */
    fun audioLabel(strings: UiStrings, codec: String?, channels: Int?, spatial: AudioSpatialKind? = null): String? {
        val name = codec?.takeIf { it.isNotBlank() }?.uppercase() ?: return null
        val suffix = when (spatial) {
            AudioSpatialKind.DOLBY_ATMOS -> "ATMOS"
            AudioSpatialKind.DTS_X -> "DTS:X"
            null -> null
        }?.takeUnless { name.contains(it) }
        return listOfNotNull(name, channelLabel(strings, channels), suffix).joinToString(" ")
    }

    private fun formatMbps(bitRate: Int, locale: Locale): String = String.format(locale, "%.1f MBPS", bitRate / 1_000_000.0)

    /**
     * docs/11 item 1's field order: RESOLUTION │ VIDEO CODEC │ BIT DEPTH │ HDR │ AUDIO(+ch) │
     * BITRATE │ CONTAINER. SIZE is omitted: [ItemDetail] carries no file-size field (TODO: needs
     * `MediaSourceInfo.size`). BITRATE reads the video stream's own bit rate as the closest
     * available stand-in for an overall source bitrate. Unknown fields are omitted, never
     * placeholdered.
     */
    fun specStripFields(strings: UiStrings, detail: ItemDetail, locale: Locale = AppLocale.format): List<SpecField> {
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
        audioLabel(strings, audio?.codec, audio?.channels, audio?.audioSpatial)?.let { out += SpecField(it, classify(it)) }
        video?.bitRate?.takeIf { it > 0 }?.let {
            val value = formatMbps(it, locale)
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
        return fields.takeIf { it.isNotEmpty() }?.joinToString(DETAILS_SEPARATOR)?.uppercaseUi()
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
     * Builds a synthetic Series [Card] from an Episode's own fields for the "View Series" pill
     * (no new FFI): `seriesId`/`seriesName`/`seriesPrimaryTag` remap onto the card, so the poster
     * resolves immediately. [parentBackdropItemId]/[parentBackdropTag] are carried over because
     * Jellyfin resolves them to the nearest ancestor with backdrop art (usually the Series), and
     * [CardFormatting.backdropArtSource] falls back to them while `backdropTag` is `null`.
     * `null` when the episode carries no `seriesId`; callers must hide the pill.
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

    private fun mediumDateFormat(locale: Locale): DateTimeFormatter = mediumDateFormatter(locale)

    /** `"Mar 6, 2014"` from an RFC3339 timestamp, `null` if absent/unparseable. Shared by
     * [runtimeAndDateLine] and [relativeAddedClause]'s "older than a month" fallback.
     */
    fun shortDate(dateStr: String?, locale: Locale = AppLocale.format): String? =
        dateStr?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }?.let { mediumDateFormat(locale).format(it) }

    /** §3 item 7 / §2 item 5's episode-grid-cell/Up-Next-panel meta line: `"{runtime} · {date}"`,
     * dropping whichever half is missing.
     */
    fun runtimeAndDateLine(strings: UiStrings, runtimeTicks: Long?, premiereDate: String?, locale: Locale = AppLocale.format): String? {
        val runtimePart = runtimeTicks?.let { CardFormatting.formatRuntime(strings, it) }
        val datePart = shortDate(premiereDate, locale)
        return join(runtimePart, datePart, THIN_SEPARATOR)
    }

    /** §2 item 2's Episode eyebrow: `"{seriesName} · S{p} E{i}"`, dropping whichever half is
     * missing (reuses [CardFormatting.seasonEpisodeLabel]'s "never S? E?" rule).
     */
    fun episodeEyebrow(strings: UiStrings, seriesName: String?, parentIndexNumber: Int?, indexNumber: Int?): String? {
        val namePart = seriesName?.takeIf { it.isNotBlank() }
        val sePart = CardFormatting.seasonEpisodeLabel(strings, parentIndexNumber, indexNumber)
        return join(namePart, sePart, THIN_SEPARATOR)
    }

    /** §2 item 2's Episode header line as constituent parts -- docs/19 §1.5's [ItemBoundaryLine]
     * needs individual items to drop trailing ones whole.
     */
    fun episodeHeaderItems(strings: UiStrings, productionYear: Int?, runtimeTicks: Long?, officialRating: String?, genres: List<String>): List<String> {
        val yearPart = productionYear?.toString()
        val runtimePart = runtimeTicks?.let { CardFormatting.formatRuntime(strings, it) }
        val ratingPart = officialRating?.takeIf { it.isNotBlank() }
        val genrePart = genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return listOfNotNull(yearPart, runtimePart, ratingPart, genrePart)
    }

    /** §2 item 2's Episode header line: `"{year} · {runtime} · {rating} · {genres}"`, each part
     * dropped when absent. Genres uncapped. Delegates to [episodeHeaderItems].
     */
    fun episodeHeaderLine(strings: UiStrings, productionYear: Int?, runtimeTicks: Long?, officialRating: String?, genres: List<String>): String? =
        episodeHeaderItems(strings, productionYear, runtimeTicks, officialRating, genres).takeIf { it.isNotEmpty() }?.joinToString(META_SEPARATOR)

    /**
     * §3 item 4's Series header line: `"{yearRange} · {n} seasons · {n} episodes · {rating} ·
     * {genres}"`. Season count from [ItemDetail.childCount], episode count from
     * [ItemDetail.recursiveItemCount] -- both `null` until the fetch resolves, omitting those
     * clauses rather than placeholdering. Genres capped at 2.
     */
    fun seriesHeaderItems(
        strings: UiStrings,
        productionYear: Int?,
        endYear: Int?,
        status: String?,
        seasonCount: Int?,
        episodeCount: Int?,
        officialRating: String?,
        genres: List<String>,
    ): List<String> {
        val yearPart = yearRangeLabel(productionYear, endYear, status)
        val seasonPart = seasonCount?.takeIf { it > 0 }?.let { strings.plural(R.plurals.detail_season_count, it, it) }
        val episodePart = episodeCount?.takeIf { it > 0 }?.let { strings.plural(R.plurals.detail_episode_count, it, it) }
        val ratingPart = officialRating?.takeIf { it.isNotBlank() }
        val genrePart = genres.filter { it.isNotBlank() }.take(2).takeIf { it.isNotEmpty() }?.joinToString(", ")
        return listOfNotNull(yearPart, seasonPart, episodePart, ratingPart, genrePart)
    }

    /** §3 item 4's Series header line -- see [seriesHeaderItems] for the parts [ItemBoundaryLine]
     * needs; this joins all of them verbatim.
     */
    fun seriesHeaderLine(
        strings: UiStrings,
        productionYear: Int?,
        endYear: Int?,
        status: String?,
        seasonCount: Int?,
        episodeCount: Int?,
        officialRating: String?,
        genres: List<String>,
    ): String? =
        seriesHeaderItems(strings, productionYear, endYear, status, seasonCount, episodeCount, officialRating, genres)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(META_SEPARATOR)

    /**
     * §3 item 6's season-selector summary: `"{SEASON NAME} · {n} EPISODES · {n} WATCHED"`, computed
     * from the loaded episode list, never a server field. [seasonName] is uppercased for this one
     * badge's display register only -- the season chip itself still shows the server's name
     * verbatim.
     */
    fun seasonSummaryLine(strings: UiStrings, seasonName: String, episodes: List<Card>): String =
        seasonSummaryItems(strings, seasonName, episodes).joinToString(THIN_SEPARATOR)

    /**
     * [seasonSummaryLine]'s three parts, unjoined -- docs/19 §1.5's [ItemBoundaryLine] drops them
     * end-first as the panel reflow narrows. Unlike every other `*Items` variant, this one is never
     * empty -- always exactly `[name, count, watched]`.
     */
    fun seasonSummaryItems(strings: UiStrings, seasonName: String, episodes: List<Card>): List<String> {
        val watched = episodes.count { it.played }
        return listOf(
            seasonName.uppercaseUi(),
            strings.plural(R.plurals.detail_episode_count_caps, episodes.size, episodes.size),
            strings.get(R.string.detail_watched_count, watched),
        )
    }

    /** §4 item 7's file-size cell: `"7.2 GB"` / `"512 MB"` -- GB at one decimal once >= 1024MB,
     * else whole-number MB. Binary (1024-based) units. `null` for absent/non-positive.
     */
    fun formatFileSize(bytes: Long?, locale: Locale = AppLocale.format): String? {
        val value = bytes?.takeIf { it > 0 } ?: return null
        val megabytes = value / (1024.0 * 1024.0)
        return if (megabytes >= 1024.0) {
            String.format(locale, "%.1f GB", megabytes / 1024.0)
        } else {
            "${megabytes.roundToLong()} MB"
        }
    }

    /**
     * ADDED row's relative clause: `"Today"` / `"Yesterday"` / `"N days ago"` (2-30 days), else
     * the bare [shortDate] once more than a month old. `null` when unparseable. Day boundaries use
     * each timestamp's own UTC-normalized date, not the system zone, keeping this deterministic.
     */
    fun relativeAddedClause(strings: UiStrings, dateCreated: String?, now: Instant = Instant.now(), locale: Locale = AppLocale.format): String? {
        val added = dateCreated?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
        val addedDate = added.toLocalDate()
        val today = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).toLocalDate()
        val days = ChronoUnit.DAYS.between(addedDate, today)
        return when {
            days <= 0L -> strings.get(R.string.detail_added_today)
            days == 1L -> strings.get(R.string.detail_added_yesterday)
            days <= 30L -> strings.plural(R.plurals.detail_added_days_ago, days.toInt(), days.toInt())
            else -> mediumDateFormat(locale).format(added)
        }
    }

    /** §4 item 3's Movie eyebrow: `"{libraryName} · ADDED {clause}"`, uppercasing only the
     * generated clause -- [libraryName] stays exactly as given (never prettified).
     */
    fun movieEyebrow(
        strings: UiStrings,
        libraryName: String?,
        dateCreated: String?,
        now: Instant = Instant.now(),
        locale: Locale = AppLocale.format,
    ): String? {
        val libraryPart = libraryName?.takeIf { it.isNotBlank() }
        val addedPart = relativeAddedClause(strings, dateCreated, now, locale)
            ?.let { strings.get(R.string.detail_added_clause, it.uppercaseUi()) }
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
    fun studioSummary(strings: UiStrings, studios: List<String>): String? {
        val clean = studios.filter { it.isNotBlank() }
        if (clean.isEmpty()) return null
        val extra = clean.size - 1
        return if (extra > 0) strings.plural(R.plurals.detail_studio_more, extra, clean.first(), extra) else clean.first()
    }

    /** The credits line's three segments, each `null` when the item has nothing for it. */
    data class CreditsLines(val directors: String?, val writers: String?, val studio: String?) {
        val isEmpty: Boolean get() = directors == null && writers == null && studio == null
    }

    /** docs/23 Rule 3's director / writer / studio segments; empty means the line is absent. */
    fun creditsLines(strings: UiStrings, directors: List<String>?, writers: List<String>?, studios: List<String>?): CreditsLines =
        CreditsLines(
            directors = peopleLine(directors.orEmpty()),
            writers = peopleLine(writers.orEmpty()),
            studio = studioSummary(strings, studios.orEmpty())?.uppercaseUi(),
        )

    /**
     * The spec capsule's cells: [specStripFields] plus [extraFields] (the Movie page's file size).
     * Empty before the detail record resolves, so one check serves "no capsule".
     */
    fun specCapsuleFields(strings: UiStrings, detail: ItemDetail?, extraFields: List<SpecField> = emptyList()): List<SpecField> =
        if (detail == null) emptyList() else specStripFields(strings, detail) + extraFields

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

    /** The first fully visible item of a shelf (same rule as [rememberCardWindow]'s latch), or null
     * when the shelf is empty; a partly scrolled-off first item is skipped.
     */
    fun shelfEntryIndex(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, itemCount: Int): Int? {
        if (itemCount <= 0) return null
        val firstFullyVisible = firstVisibleIndex + if (firstVisibleOffsetPx > 0) 1 else 0
        return firstFullyVisible.coerceIn(0, itemCount - 1)
    }
}
