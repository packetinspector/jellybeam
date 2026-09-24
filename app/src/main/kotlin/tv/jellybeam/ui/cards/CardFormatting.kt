package tv.jellybeam.ui.cards

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind

/** Pure Kotlin ports of the card-text formatting rules in `docs/07-home-browse-behavior.md` §1-2,
 * free of any Compose/Android dependency so they're plain-JVM-testable.
 */
object CardFormatting {

    /** Ticks per second in Jellyfin's tick unit (100ns ticks). */
    private const val TICKS_PER_SECOND = 10_000_000L

    /** "1h 30m" / "45m", never seconds. Sub-minute (non-zero) durations round up to "1m" -- a
     * literal "0m" reads as broken metadata.
     */
    fun formatRuntime(ticks: Long): String {
        val totalSeconds = ticks / TICKS_PER_SECOND
        val totalMinutes = (totalSeconds + 59) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** §2: `"E{n} · {name}"`, not "{n}. {name}"; bare name if there's no index number. */
    fun episodeTitle(name: String, indexNumber: Int?): String =
        if (indexNumber != null) "E$indexNumber · $name" else name

    /** `season_episode_label`: drops whichever half is missing, never "S? E?". */
    fun seasonEpisodeLabel(parentIndexNumber: Int?, indexNumber: Int?): String? =
        when {
            parentIndexNumber != null && indexNumber != null -> "S$parentIndexNumber E$indexNumber"
            parentIndexNumber == null && indexNumber != null -> "E$indexNumber"
            parentIndexNumber != null && indexNumber == null -> "S$parentIndexNumber"
            else -> null
        }

    /**
     * `runtime - position`, formatted via [formatRuntime] plus " left"; `null` when there's no
     * runtime to compute from. An unstarted item (`positionTicks == 0`) shows plain runtime with
     * no " left" suffix -- there's nothing in progress to be left of.
     */
    fun remainingLabel(runtimeTicks: Long?, positionTicks: Long): String? {
        val runtime = runtimeTicks ?: return null
        if (runtime <= 0) return null
        if (positionTicks <= 0) return formatRuntime(runtime)
        val remaining = (runtime - positionTicks).coerceAtLeast(0)
        return "${formatRuntime(remaining)} left"
    }

    /** "Airs {abbrev date}" for a parseable, future `premiereDate` (RFC3339); "Missing" otherwise
     * (absent, unparseable, or past).
     */
    fun virtualStatusLabel(premiereDate: String?, now: Instant = Instant.now()): String {
        val airsInFuture = premiereDate
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?.takeIf { it.toInstant().isAfter(now) }
        return if (airsInFuture != null) {
            "Airs ${AIRS_DATE_FORMAT.format(airsInFuture)}"
        } else {
            "Missing"
        }
    }

    private val AIRS_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** Resume/next-up card's third line: season/episode + (remaining time, or virtual-status for a
     * virtual episode), joined with " · "; `null` when neither applies.
     */
    fun resumeMetaLine(card: Card): String? {
        val se = seasonEpisodeLabel(card.parentIndexNumber, card.indexNumber)
        val timePart = if (card.isVirtual) {
            virtualStatusLabel(card.premiereDate)
        } else {
            remainingLabel(card.runtimeTicks, card.positionTicks)
        }
        return when {
            se != null && timePart != null -> "$se · $timePart"
            se != null -> se
            timePart != null -> timePart
            else -> null
        }
    }

    /** §7: the timing half of [resumeMetaLine], shown on the card's art ([TimingPill]) rather than
     * the text block below it.
     */
    fun resumeTimingLabel(card: Card): String? =
        if (card.isVirtual) virtualStatusLabel(card.premiereDate) else remainingLabel(card.runtimeTicks, card.positionTicks)

    /** §7's resume-shelf second line: `"{seriesName} · S{season}"` for an episode, dropping
     * whichever half is missing; `null` for a non-episode card.
     */
    fun resumeSeriesSeasonLine(card: Card): String? {
        if (card.itemType != "Episode") return null
        val season = card.parentIndexNumber?.let { "S$it" }
        val seriesName = card.seriesName?.takeIf { it.isNotBlank() }
        return when {
            seriesName != null && season != null -> "$seriesName · $season"
            seriesName != null -> seriesName
            season != null -> season
            else -> null
        }
    }

    /** Fraction clamped 0..1, only when runtime is present and position > 0. */
    fun watchProgress(card: Card): Float? {
        val runtime = card.runtimeTicks ?: return null
        if (card.positionTicks <= 0) return null
        return (card.positionTicks.toDouble() / runtime.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    private val COUNTABLE_TYPES = setOf("Series", "Season", "BoxSet")

    /** `watch_indicator`: unplayed-count pill (countable types) XOR watched check -- never both. */
    fun watchIndicator(card: Card, progress: Float?): WatchIndicator {
        if (card.itemType in COUNTABLE_TYPES) {
            val count = card.unplayedCount
            return if (count != null && count > 0) WatchIndicator.UnplayedCount(count) else WatchIndicator.None
        }
        return if (card.played && progress == null) WatchIndicator.WatchedCheck else WatchIndicator.None
    }

    /** Resolves an [ArtSource] to its URL via [imageUrl] (width baked into the caller's closure),
     * `null` for [ArtSource.None]. Shared by [CardArtImage]'s own fetch and Detail's
     * placeholder-cache-key derivation, so both follow the same fallback chain.
     */
    fun resolveArtUrl(source: ArtSource, imageUrl: (itemId: String, kind: ImageKind, tag: String) -> String?): String? =
        when (source) {
            is ArtSource.Own -> imageUrl(source.itemId, source.kind, source.tag)
            is ArtSource.Fallback -> imageUrl(source.itemId, source.kind, source.tag)
            ArtSource.None -> null
        }

    /** [Card] art-fallback chain for a 2:3 poster slot. */
    fun posterArtSource(card: Card): ArtSource {
        if (card.itemType != "Episode") {
            card.primaryTag?.let { return ArtSource.Own(card.id, it, ImageKind.PRIMARY) }
        }
        val seriesId = card.seriesId
        val seriesTag = card.seriesPrimaryTag
        return if (seriesId != null && seriesTag != null) {
            ArtSource.Fallback(seriesId, seriesTag, ImageKind.PRIMARY)
        } else {
            ArtSource.None
        }
    }

    /** [Card] art-fallback chain for a 16:9 rail/thumb slot: the item's own PRIMARY (an episode's
     * Primary is its landscape still), else the parent's BACKDROP.
     */
    fun railArtSource(card: Card): ArtSource {
        card.primaryTag?.let { return ArtSource.Own(card.id, it, ImageKind.PRIMARY) }
        val parentId = card.parentBackdropItemId
        val parentTag = card.parentBackdropTag
        return if (parentId != null && parentTag != null) {
            ArtSource.Fallback(parentId, parentTag, ImageKind.BACKDROP)
        } else {
            ArtSource.None
        }
    }

    /**
     * [Card] art chain for a full-bleed hero/header slot: the item's own BACKDROP, else the
     * parent's backdrop, else the 16:9 rail chain (an episode's landscape Primary is a fine hero;
     * a non-episode without a backdrop falls to [ArtSource.None] rather than stretching a poster).
     */
    fun backdropArtSource(card: Card): ArtSource {
        card.backdropTag?.let { return ArtSource.Own(card.id, it, ImageKind.BACKDROP) }
        val parentId = card.parentBackdropItemId
        val parentTag = card.parentBackdropTag
        if (parentId != null && parentTag != null) {
            return ArtSource.Fallback(parentId, parentTag, ImageKind.BACKDROP)
        }
        if (card.itemType == "Episode") {
            card.primaryTag?.let { return ArtSource.Own(card.id, it, ImageKind.PRIMARY) }
        }
        return ArtSource.None
    }

    /**
     * [Card] art source for a Continue-Watching/Next-Up tile. Deviates from docs/07 §1's
     * mixed-aspect row: the resume shelf is uniform 16:9, so a movie uses [backdropArtSource]
     * (never stretches a poster) rather than [posterArtSource]; episodes still use [railArtSource].
     */
    fun resumeArtSource(card: Card): ArtSource =
        if (card.itemType == "Episode") railArtSource(card) else backdropArtSource(card)

    /**
     * §9's resume/next-up sizing: 16:9 episode-card width fitting 6.5 across `shelfWidthDp`,
     * clamped to [172, 280]dp. [cellGapDp]'s default (12f) matches HomeScreen's `CELL_GAP`. In
     * practice the 172dp floor (172*9/16 ~ 97dp tall) is the load-bearing number at typical shelf
     * widths, not the across-count.
     */
    fun resumeCardWidthDp(shelfWidthDp: Float, cellGapDp: Float = 12f): Float {
        val width = (shelfWidthDp + cellGapDp) / 6.5f - cellGapDp
        return width.coerceIn(172f, 280f)
    }

    /** The episode width's 16:9 height. */
    fun resumeRowHeightDp(shelfWidthDp: Float, cellGapDp: Float = 12f): Float =
        resumeCardWidthDp(shelfWidthDp, cellGapDp) * 9f / 16f

    /**
     * The server-side image widths a fetch is allowed to ask for -- a small fixed set so cards
     * whose displayed size differs by a few dp still request the same rendition and hit the
     * server's image cache instead of fragmenting it.
     */
    private val IMAGE_WIDTH_BUCKETS = intArrayOf(240, 360, 480, 720, 1280)

    /** Rounds [requestedPx] up to the smallest [IMAGE_WIDTH_BUCKETS] entry that covers it, clamped
     * to the largest bucket beyond that.
     */
    fun bucketedImageWidth(requestedPx: Int): UInt =
        (IMAGE_WIDTH_BUCKETS.firstOrNull { requestedPx <= it } ?: IMAGE_WIDTH_BUCKETS.last()).toUInt()

    /** Detail header metadata line: `"{year} · {runtime}"`, dropping whichever half is missing. */
    fun detailMetaLine(productionYear: Int?, runtimeTicks: Long?): String? {
        val yearPart = productionYear?.toString()
        val runtimePart = runtimeTicks?.let { formatRuntime(it) }
        return when {
            yearPart != null && runtimePart != null -> "$yearPart · $runtimePart"
            yearPart != null -> yearPart
            runtimePart != null -> runtimePart
            else -> null
        }
    }

    /** Hero banner metadata line (docs/07 §1): `"S{s} E{e} · {runtime} · {year}"` for an episode,
     * dropping missing parts; bare `"{year}"` otherwise.
     */
    fun heroMetaLine(card: Card): String? {
        if (card.itemType != "Episode") {
            return card.productionYear?.toString()
        }
        val parts = listOfNotNull(
            seasonEpisodeLabel(card.parentIndexNumber, card.indexNumber),
            card.runtimeTicks?.let { formatRuntime(it) },
            card.productionYear?.toString(),
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Hero primary button state (docs/07 §1): "Resume" once progress exists, "Play" for unstarted
     * -- `positionTicks > 0`, without requiring a known runtime.
     */
    fun heroIsResumable(card: Card): Boolean = card.positionTicks > 0

    /** Detail Play button state: disabled with [virtualStatusLabel] for a virtual item, otherwise
     * playable.
     */
    fun playButtonState(card: Card, now: Instant = Instant.now()): PlayButtonState =
        if (card.isVirtual) {
            PlayButtonState.Unavailable(virtualStatusLabel(card.premiereDate, now))
        } else {
            PlayButtonState.Playable
        }
}

/** [CardFormatting.playButtonState]'s result: whether/why a Detail screen's Play button is
 * disabled.
 */
sealed interface PlayButtonState {
    data object Playable : PlayButtonState
    data class Unavailable(val label: String) : PlayButtonState
}

/**
 * Where a card's art should come from, per the fallback chains above. Each case carries the
 * [ImageKind] alongside the id/tag pair: falling back from "own primary" to "parent backdrop"
 * changes the image type too, not just whose id it fetches against.
 */
sealed interface ArtSource {
    /** The item's own image: `(itemId, kind, tag)`. */
    data class Own(val itemId: String, val tag: String, val kind: ImageKind) : ArtSource

    /** An ancestor's image (series poster / parent backdrop): `(itemId, kind, tag)`. */
    data class Fallback(val itemId: String, val tag: String, val kind: ImageKind) : ArtSource

    /** No usable art anywhere in the chain -- render the named placeholder. */
    data object None : ArtSource
}

/** What (if anything) a card's watch-state badge should show. */
sealed interface WatchIndicator {
    data class UnplayedCount(val count: Long) : WatchIndicator
    data object WatchedCheck : WatchIndicator
    data object None : WatchIndicator
}
