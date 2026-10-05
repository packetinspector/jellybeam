package tv.jellybeam.ui.detail

import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.WatchIndicator
import tv.jellybeam.ui.common.countLabel
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind

/**
 * Pure rules for Jellyfin BoxSet (collection) UI (docs/11 §Collection, docs/07 §Collection card),
 * same discipline as [DetailFormatting]: members arrive in server display order and every
 * decision below is a function of them.
 */
object CollectionFormatting {

    const val BOXSET_ITEM_TYPE = "BoxSet"
    private const val SERIES_ITEM_TYPE = "Series"
    private const val SEASON_ITEM_TYPE = "Season"
    private const val SPECIALS_SEASON_NAME = "Specials"

    /** Members Play can start; a nested collection, album, book or photo never is the target. */
    private val PLAYABLE_ITEM_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo", SERIES_ITEM_TYPE)

    const val LABEL_PLAY = "Play"
    const val LABEL_RESUME = "Resume"
    const val LABEL_PLAY_AGAIN = "Play again"

    /** Meta line separator (mono caps line under the title). */
    const val META_SEPARATOR = " | "

    /** Posters in a stacked card: the front plus up to two fanned behind it. */
    const val STACK_DEPTH = 3

    /** "Watched" for a member: Series needs every episode played; a resume-in-progress is not. */
    fun isWatched(member: Card): Boolean =
        if (member.itemType == SERIES_ITEM_TYPE) {
            member.played && (member.unplayedCount ?: 0L) <= 0L
        } else {
            member.played && member.positionTicks <= 0
        }

    fun watchedCount(members: List<Card>): Int = members.count(::isWatched)

    /** [member] is what Play lands on; [replay] means every playable member was already watched. */
    data class PlayTarget(val member: Card, val replay: Boolean)

    /**
     * Play targets in preference order, same as the series page's pill: members part-way through,
     * then the rest not fully watched; only when none is left, every member to replay. Non-virtual
     * playable types only.
     */
    fun playCandidates(members: List<Card>): List<PlayTarget> {
        val playable = members.filter { !it.isVirtual && it.itemType in PLAYABLE_ITEM_TYPES }
        val partWay = playable.filter { it.itemType != SERIES_ITEM_TYPE && it.positionTicks > 0 }
        val next = partWay + playable.filter { !isWatched(it) && it !in partWay }
        return if (next.isNotEmpty()) {
            next.map { PlayTarget(it, replay = false) }
        } else {
            playable.map { PlayTarget(it, replay = true) }
        }
    }

    fun playTarget(members: List<Card>): PlayTarget? = playCandidates(members).firstOrNull()

    /**
     * The pill's action: the first candidate that resolves, a Series to its episode via
     * [episodesOf]. A Series with no episode yields to the next candidate; one whose fetch fails
     * (`null`) keeps [previous] if it already targeted that series, else yields too.
     */
    suspend fun resolvePlay(
        members: List<Card>,
        previous: CollectionPlay?,
        episodesOf: suspend (Card) -> List<Card>?,
    ): CollectionPlay? {
        for (target in playCandidates(members)) {
            if (target.member.itemType != SERIES_ITEM_TYPE) {
                return CollectionPlay(target.member, episode = null, fromStart = target.replay, replayAll = target.replay)
            }
            val episodes = episodesOf(target.member)
                ?: if (previous?.member?.id == target.member.id) return previous else continue
            val pick = seriesPlayEpisode(target.member, episodes, target.replay) ?: continue
            return CollectionPlay(target.member, pick.episode, fromStart = pick.fromStart, replayAll = target.replay)
        }
        return null
    }

    /** A Series target resolved to the episode to start; [fromStart] ignores a saved position. */
    data class EpisodePick(val episode: Card, val fromStart: Boolean)

    /**
     * Same order as the series page's Play pill ([DetailFormatting.resolvePrimaryAction]): first
     * episode with progress, else first unplayed; [replay] (or nothing left unplayed) starts the
     * first non-Specials episode from the beginning. `null` when the series has no episode.
     */
    fun seriesPlayEpisode(series: Card, episodes: List<Card>, replay: Boolean): EpisodePick? {
        if (!replay) {
            val action = DetailFormatting.resolvePrimaryAction(series, episodes)
            if (action is DetailFormatting.PrimaryAction.Playable) {
                episodes.firstOrNull { it.id == action.targetId }?.let { return EpisodePick(it, fromStart = false) }
            }
        }
        val playable = episodes.filterNot { it.isVirtual }
        val first = playable.firstOrNull { it.parentIndexNumber != 0 } ?: playable.firstOrNull() ?: return null
        return EpisodePick(first, fromStart = true)
    }

    /** What the Play pill does: [replayAll] flips its label; [episode] is set for a Series member. */
    data class CollectionPlay(val member: Card, val episode: Card?, val fromStart: Boolean, val replayAll: Boolean) {
        val targetId: String get() = (episode ?: member).id

        /** Picks up a saved position, as every other Play pill labels "Resume". */
        val resuming: Boolean get() = !fromStart && (episode ?: member).positionTicks > 0
    }

    fun playLabel(play: CollectionPlay): String = when {
        play.replayAll -> LABEL_PLAY_AGAIN
        play.resuming -> LABEL_RESUME
        else -> LABEL_PLAY
    }

    /**
     * `"<name> · <runtime>"` (`"<name> · 53m left"` when resuming), or `"<series name> · S2 E6"` for a
     * Series target (verbatim names).
     */
    fun playSubtext(play: CollectionPlay): String? {
        val episode = play.episode
        val detail = if (episode != null) {
            val season = episode.parentIndexNumber
            val number = episode.indexNumber
            if (season != null && number != null) "S$season E$number" else null
        } else {
            CardFormatting.remainingLabel(play.member.runtimeTicks, if (play.resuming) play.member.positionTicks else 0)
        }
        return if (detail != null) "${play.member.name} · $detail" else play.member.name
    }

    /** `"2007–2026"` over members' production years; one year if equal; `null` if none. */
    fun yearRange(members: List<Card>): String? {
        val years = members.mapNotNull { it.productionYear }
        val min = years.minOrNull() ?: return null
        val max = years.max()
        return if (min == max) "$min" else "$min–$max"
    }

    /** `"6 ITEMS | 5 WATCHED | 2007–2026"`; the watched segment hides when empty, years when none. */
    fun metaItems(members: List<Card>): List<String> {
        val items = mutableListOf(countLabel(members.size, "ITEM", "ITEMS"))
        if (members.isNotEmpty()) items += "${watchedCount(members)} WATCHED"
        yearRange(members)?.let { items += it }
        return items
    }

    fun metaLine(members: List<Card>): String = metaItems(members).joinToString(META_SEPARATOR)

    /** Seasons of a series, Specials excluded (matches the series page's chip row count). */
    fun seasonCount(seriesChildren: List<Card>): Int = seriesChildren.count {
        it.itemType == SEASON_ITEM_TYPE && it.name != SPECIALS_SEASON_NAME && it.indexNumber != 0
    }

    /**
     * A member poster's caption line under its title: `"Series · 12 seasons"` / `"2025 · 1h 42m"`;
     * `null` keeps the shared poster line (an Episode's own).
     */
    fun cardSubline(member: Card, seasonCount: Int?): String? = when (member.itemType) {
        SERIES_ITEM_TYPE -> if (seasonCount != null) "Series · ${countLabel(seasonCount, "season", "seasons")}" else "Series"
        "Episode" -> null
        else -> CardFormatting.detailMetaLine(member.productionYear, member.runtimeTicks)
    }

    /** Detail backdrop: the collection's own, else the first member that has a backdrop. */
    fun backdropSource(collection: Card, members: List<Card>): ArtSource {
        collection.backdropTag?.let { return ArtSource.Own(collection.id, it, ImageKind.BACKDROP) }
        for (member in members) {
            val source = CardFormatting.backdropArtSource(member)
            val isBackdrop = when (source) {
                is ArtSource.Own -> source.kind == ImageKind.BACKDROP
                is ArtSource.Fallback -> source.kind == ImageKind.BACKDROP
                ArtSource.None -> false
            }
            if (isBackdrop) return source
        }
        return ArtSource.None
    }

    /**
     * Posters of a stacked library card, front first: the collection's own primary image if it
     * has one (then the next members behind it), else member[0] in front of the following ones.
     * Empty when there is nothing to draw yet.
     */
    fun stackLayers(collection: Card, members: List<Card>): List<Card> {
        val front = if (collection.primaryTag != null) collection else members.firstOrNull() ?: return emptyList()
        val behind = members.filter { it.id != front.id }
        return (listOf(front) + behind).take(STACK_DEPTH)
    }

    /**
     * Unwatched members, counted the way the collection page's meta line counts them; the server's
     * own count (episodes inside a Series member included) only until the members load.
     */
    private fun unplayedOf(card: Card, members: List<Card>?): Long? = when {
        !members.isNullOrEmpty() -> members.count { !isWatched(it) }.toLong()
        else -> card.unplayedCount ?: if (card.played) 0L else null
    }

    /** `"8 UNPLAYED"` / `"ALL WATCHED"`; `null` when unknown or the collection is known empty. */
    fun stackCaption(card: Card, preview: List<Card>?): String? {
        if (preview != null && preview.isEmpty()) return null
        val unplayed = unplayedOf(card, preview) ?: return null
        return if (unplayed > 0) "$unplayed UNPLAYED" else "ALL WATCHED"
    }

    /** The collection card's badge: how many items it holds, once its members load (docs/07 §Collection card). */
    fun stackIndicator(preview: List<Card>?): WatchIndicator =
        if (preview.isNullOrEmpty()) WatchIndicator.None else WatchIndicator.Count(preview.size.toLong())
}
