package tv.jellybeam.ui.detail

import kotlin.random.Random
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.ui.common.youtubeKey
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.CollectionInfo
import uniffi.jellybeam_core.ExtraItem
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.RemoteTrailer

/**
 * Pure, plain-Kotlin content model for the detail-page action panel (docs/19-detail-action-menu.md
 * §1/§3.2), same discipline as [DetailFormatting]. [buildMenu] evaluates every presence rule in
 * §1.1; the Compose layer ([DetailActionPanel]) only renders [MenuModel] and dispatches
 * [MenuAction] to [DetailViewModel.runAction]. Labels come from [UiStrings] (docs/27 §3), keeping
 * this JVM-testable.
 */

private const val MOVIE_ITEM_TYPE = "Movie"
private const val SERIES_ITEM_TYPE = "Series"
private const val EPISODE_ITEM_TYPE = "Episode"
private const val BOXSET_ITEM_TYPE = "BoxSet"

enum class MenuGroup { THIS_TITLE, PLAYBACK, LIBRARY }

sealed interface MenuAction {
    data object MarkWatched : MenuAction
    data object MarkUnwatched : MenuAction

    /** [isSeason] tells the Compose layer "Mark series" from "Mark season" without re-deriving. */
    data class MarkScopeWatched(val count: Int, val isSeason: Boolean) : MenuAction
    data class MarkScopeUnwatched(val count: Int, val isSeason: Boolean) : MenuAction

    data object AddFavorite : MenuAction
    data object RemoveFavorite : MenuAction

    data class PlayNextUnwatched(val targetId: String, val subtext: String?) : MenuAction
    data class PlayFromBeginning(val targetId: String) : MenuAction
    data class PlayRandom(val count: Int) : MenuAction
    /** docs/28 §1: opens the lobby holding [targetId], with its resume position for the host's Start choice. */
    data class WatchTogether(val targetId: String, val resumeTicks: Long) : MenuAction

    data class GoToSeries(val series: Card) : MenuAction
    data object AddToCollection : MenuAction

    /** docs/19 §1.3: the door to the Trailers and extras level. */
    data object Extras : MenuAction
    data object RefreshMetadata : MenuAction
}

data class MenuRow(val action: MenuAction, val label: String, val subtext: String?)
data class MenuGroupModel(val group: MenuGroup, val rows: List<MenuRow>)

/**
 * [focusOn] is the exact [MenuAction] instance docs/19 §1.2's prediction table names, so the
 * panel matches by equality; `null` falls back to row 0.
 */
data class MenuModel(val groups: List<MenuGroupModel>, val focusOn: MenuAction?)

/**
 * [scopeSeason]: `null` at series-wide scope, or for Movie/Episode; set only once the viewer
 * explicitly picks a season chip (an auto-resolved resume-season highlight does not flip scope).
 * [scopeEpisodes]: the scoped season's episodes, empty unless [scopeSeason] is set.
 * [allEpisodes]: every episode of the series, all seasons; empty for Movie/Episode.
 * [isFavorite]: always the series' flag in season scope (docs/19 §1.1).
 */
data class MenuInput(
    val card: Card,
    val itemType: String,
    val scopeSeason: Card?,
    val scopeEpisodes: List<Card>,
    val allEpisodes: List<Card>,
    val primaryAction: DetailFormatting.PrimaryAction,
    val isFavorite: Boolean,
    val hasCollections: Boolean,
    val isAdministrator: Boolean,
    val seriesCard: Card?,
    /** Season the strip highlights (selected or auto-resolved); only the "Play next unwatched"
     * subtext reads it. Distinct from [scopeSeason].
     */
    val highlightedSeasonNumber: Int? = null,
    /** [extrasAvailable]'s answer; gates the [MenuAction.Extras] row. */
    val extrasAvailable: Boolean = false,
)

/** docs/19 §1.1/§1.2: builds every group/row this input warrants, and which row focuses first. */
fun buildMenu(strings: UiStrings, input: MenuInput): MenuModel {
    val thisTitleRows = buildThisTitleRows(strings, input)
    val playbackRows = buildPlaybackRows(strings, input)
    val libraryRows = buildLibraryRows(strings, input)

    val groups = listOfNotNull(
        thisTitleRows.takeIf { it.isNotEmpty() }?.let { MenuGroupModel(MenuGroup.THIS_TITLE, it) },
        playbackRows.takeIf { it.isNotEmpty() }?.let { MenuGroupModel(MenuGroup.PLAYBACK, it) },
        libraryRows.takeIf { it.isNotEmpty() }?.let { MenuGroupModel(MenuGroup.LIBRARY, it) },
    )

    // §1.2: Series/season also counts "already played", asymmetric with Movie/Episode's
    // position-only rule.
    val hasProgress = if (input.itemType == SERIES_ITEM_TYPE) {
        val scope = input.scopeSeason?.let { input.scopeEpisodes } ?: input.allEpisodes
        scope.any { it.played || it.positionTicks > 0 }
    } else {
        input.card.positionTicks > 0
    }
    val allRows = groups.flatMap { it.rows }
    // Looks the predicted action up among the actual rows built above, so the panel gets the
    // exact row instance to match by equality; absent -> `null` (fall back to row 0, §1.2).
    fun firstRowMatching(predicate: (MenuAction) -> Boolean): MenuAction? =
        allRows.firstOrNull { predicate(it.action) }?.action

    val focusOn = when (input.itemType) {
        SERIES_ITEM_TYPE -> if (hasProgress) {
            firstRowMatching { it is MenuAction.PlayNextUnwatched }
        } else {
            // "Series, never played" (docs/19 §1.2): falls through to the season mark-watched row,
            // else row 0.
            firstRowMatching { it is MenuAction.MarkScopeWatched }
        }
        EPISODE_ITEM_TYPE -> firstRowMatching { it is MenuAction.PlayFromBeginning }
        // docs/19 §Collection: no mark/play rows, so the first row (favorite) takes focus.
        BOXSET_ITEM_TYPE -> null
        else -> when {
            hasProgress -> firstRowMatching { it is MenuAction.PlayFromBeginning }
            !input.card.played -> MenuAction.MarkWatched
            else -> null
        }
    }

    return MenuModel(groups, focusOn)
}

private fun buildThisTitleRows(strings: UiStrings, input: MenuInput): List<MenuRow> {
    val rows = mutableListOf<MenuRow>()
    if (input.itemType == SERIES_ITEM_TYPE) {
        val isSeason = input.scopeSeason != null
        val scopeEpisodes = (if (isSeason) input.scopeEpisodes else input.allEpisodes).filterNot { it.isVirtual }
        val unwatchedCount = scopeEpisodes.count { !it.played }
        val watchedCount = scopeEpisodes.count { it.played }
        if (unwatchedCount > 0) {
            rows += MenuRow(
                action = MenuAction.MarkScopeWatched(unwatchedCount, isSeason),
                label = strings.get(if (isSeason) R.string.detail_menu_mark_season_watched else R.string.detail_menu_mark_series_watched),
                subtext = strings.plural(R.plurals.detail_episode_count_caps, unwatchedCount, unwatchedCount),
            )
        }
        if (watchedCount > 0) {
            rows += MenuRow(
                action = MenuAction.MarkScopeUnwatched(watchedCount, isSeason),
                label = strings.get(if (isSeason) R.string.detail_menu_mark_season_unwatched else R.string.detail_menu_mark_series_unwatched),
                subtext = strings.plural(R.plurals.detail_episode_count_caps, watchedCount, watchedCount),
            )
        }
    } else if (input.itemType != BOXSET_ITEM_TYPE) {
        rows += if (input.card.played) {
            MenuRow(MenuAction.MarkUnwatched, strings.get(R.string.detail_menu_mark_unwatched), null)
        } else {
            // §1.1: `NEVER PLAYED` when there's also no saved position.
            val neverPlayed = input.card.positionTicks <= 0
            MenuRow(
                MenuAction.MarkWatched,
                strings.get(R.string.detail_menu_mark_watched),
                if (neverPlayed) strings.get(R.string.detail_menu_never_played) else null,
            )
        }
    }
    // §1.1: this row reads/writes the series flag even in season scope; `SERIES` marks that in the
    // state column.
    val isSeasonScope = input.itemType == SERIES_ITEM_TYPE && input.scopeSeason != null
    val seriesState = if (isSeasonScope) strings.get(R.string.detail_menu_state_series) else null
    rows += if (input.isFavorite) {
        MenuRow(MenuAction.RemoveFavorite, strings.get(R.string.detail_menu_remove_favorite), seriesState)
    } else {
        MenuRow(MenuAction.AddFavorite, strings.get(R.string.detail_menu_add_favorite), seriesState)
    }
    return rows
}

private fun buildPlaybackRows(strings: UiStrings, input: MenuInput): List<MenuRow> {
    val rows = mutableListOf<MenuRow>()
    // docs/19 §Collection: a collection plays from its page's pill, never from the menu.
    if (input.itemType == BOXSET_ITEM_TYPE) return rows
    if (input.itemType == SERIES_ITEM_TYPE) {
        val isSeason = input.scopeSeason != null
        val scopeEpisodes = (if (isSeason) input.scopeEpisodes else input.allEpisodes).filterNot { it.isVirtual }
        if (isSeason) {
            val target = scopeEpisodes.firstOrNull { it.positionTicks > 0 } ?: scopeEpisodes.firstOrNull { !it.played }
            if (target != null) {
                val subtext = playNextUnwatchedSubtext(strings, target, input.scopeSeason?.indexNumber)
                rows += MenuRow(MenuAction.PlayNextUnwatched(target.id, subtext), strings.get(R.string.detail_menu_play_next_unwatched), subtext)
            }
        } else {
            val action = input.primaryAction
            if (action is DetailFormatting.PrimaryAction.Playable) {
                val target = input.allEpisodes.firstOrNull { it.id == action.targetId }
                val subtext = target?.let { playNextUnwatchedSubtext(strings, it, input.highlightedSeasonNumber) }
                rows += MenuRow(MenuAction.PlayNextUnwatched(action.targetId, subtext), strings.get(R.string.detail_menu_play_next_unwatched), subtext)
                if (action.hasProgress) {
                    rows += MenuRow(MenuAction.PlayFromBeginning(action.targetId), strings.get(R.string.detail_menu_play_from_beginning), null)
                }
            }
        }
        if (scopeEpisodes.isNotEmpty()) {
            rows += MenuRow(
                action = MenuAction.PlayRandom(scopeEpisodes.size),
                label = strings.get(R.string.detail_menu_play_random),
                subtext = strings.get(R.string.detail_menu_play_random_from, scopeEpisodes.size),
            )
        }
    } else {
        val action = input.primaryAction
        if (action is DetailFormatting.PrimaryAction.Playable && action.hasProgress) {
            rows += MenuRow(MenuAction.PlayFromBeginning(action.targetId), strings.get(R.string.detail_menu_play_from_beginning), null)
        }
    }
    // docs/28 §1: the group plays what Play would, so it exists only with a playable target.
    val action = input.primaryAction
    if (action is DetailFormatting.PrimaryAction.Playable) {
        val target = if (action.targetId == input.card.id) input.card else input.allEpisodes.firstOrNull { it.id == action.targetId }
        rows += MenuRow(MenuAction.WatchTogether(action.targetId, target?.positionTicks ?: 0L), strings.get(R.string.detail_menu_watch_together), null)
    }
    // docs/19 §1.3: last in the group, so it never moves the focus prediction (§1.2); its state column is the `▸` marker.
    if (input.extrasAvailable) rows += MenuRow(MenuAction.Extras, strings.get(R.string.detail_menu_extras), null)
    return rows
}

/**
 * §1.1: `E{n}` in the highlighted season, else `S{s} E{n}`; `null` if a number is missing, never
 * a partial `S? E?` (same rule as [DetailFormatting]'s `strictSeasonEpisode`).
 */
private fun playNextUnwatchedSubtext(strings: UiStrings, target: Card, highlightedSeasonNumber: Int?): String? {
    val episodeNumber = target.indexNumber ?: return null
    if (highlightedSeasonNumber != null && target.parentIndexNumber == highlightedSeasonNumber) {
        return strings.get(R.string.detail_episode_number, episodeNumber)
    }
    val seasonNumber = target.parentIndexNumber ?: return null
    return strings.get(R.string.detail_season_episode, seasonNumber, episodeNumber)
}

private fun buildLibraryRows(strings: UiStrings, input: MenuInput): List<MenuRow> {
    val rows = mutableListOf<MenuRow>()
    if (input.itemType == EPISODE_ITEM_TYPE && input.seriesCard != null) {
        // Verbatim server name, never re-cased (CLAUDE.md hard rule).
        rows += MenuRow(MenuAction.GoToSeries(input.seriesCard), strings.get(R.string.detail_menu_go_to_series), input.seriesCard.name)
    }
    val isSeasonScope = input.itemType == SERIES_ITEM_TYPE && input.scopeSeason != null
    // docs/19 §Collection: adding a collection to a collection is not offered.
    if (!isSeasonScope && input.hasCollections && input.itemType != BOXSET_ITEM_TYPE) {
        rows += MenuRow(MenuAction.AddToCollection, strings.get(R.string.detail_menu_add_to_collection), null)
    }
    if (input.isAdministrator) {
        // §1.1: always acts on the whole series, so season scope marks it `SERIES` too.
        rows += MenuRow(
            MenuAction.RefreshMetadata,
            strings.get(R.string.detail_menu_refresh_metadata),
            if (isSeasonScope) strings.get(R.string.detail_menu_state_series) else null,
        )
    }
    return rows
}

/** docs/19 §1.3: one row per collection, server order; `ALREADY IN` rows aren't focusable. */
data class CollectionRow(val collection: CollectionInfo, val alreadyIn: Boolean)

/** Pure so [DetailMenuModelTest] covers it without Compose; the panel calls this directly. */
fun buildCollectionRows(collections: List<CollectionInfo>, memberOfCollections: Set<String>): List<CollectionRow> =
    collections.map { CollectionRow(it, alreadyIn = it.id in memberOfCollections) }

/** docs/19 §1.3: what the Movie page's Trailer pill plays. */
sealed interface PillTrailer {
    data object Local : PillTrailer
    data class Remote(val url: String) : PillTrailer
}

/** docs/19 §1.3: a row of the Trailers and extras level. */
sealed interface ExtraEntry {
    data class Local(val item: ExtraItem) : ExtraEntry
    data class Remote(val name: String?, val url: String) : ExtraEntry
}

/** docs/19 §1.3: the extras level's load state; [Idle] until the menu first opens with the row present. */
sealed interface ExtrasState {
    data object Idle : ExtrasState
    data object Loading : ExtrasState
    data class Loaded(val entries: List<ExtraEntry>) : ExtrasState
    data object Failed : ExtrasState
}

/** docs/19 §1.3: Movie only; a server trailer wins, else the first remote link an installed app can open. */
fun pillTrailer(itemType: String, detail: ItemDetail?, canOpenRemote: (String) -> Boolean): PillTrailer? {
    if (itemType != MOVIE_ITEM_TYPE || detail == null) return null
    if (detail.localTrailerCount > 0) return PillTrailer.Local
    return detail.remoteTrailers.firstOrNull { canOpenRemote(it.url) }?.let { PillTrailer.Remote(it.url) }
}

/** docs/19 §1.3: from the detail's counts alone, so the menu opens without a fetch; a Movie's pill plays the first trailer. */
fun extrasAvailable(itemType: String, detail: ItemDetail?, canOpenRemote: (String) -> Boolean): Boolean {
    if (detail == null || itemType == BOXSET_ITEM_TYPE) return false
    val trailers = detail.localTrailerCount + detail.remoteTrailers.count { canOpenRemote(it.url) }
    return if (itemType == MOVIE_ITEM_TYPE) {
        trailers > 1 || detail.specialFeatureCount > 0
    } else {
        trailers + detail.specialFeatureCount > 0
    }
}

private const val TRAILER_EXTRA_TYPE = "Trailer"
private const val THEME_SONG_EXTRA_TYPE = "ThemeSong"

/** docs/19 §1.3: features follow this type order, then name; any other type, or none, sorts last. */
private val FEATURE_TYPE_ORDER = listOf("BehindTheScenes", "Featurette", "Interview", "Scene", "DeletedScene", "Short", "Clip", "Sample")

private fun featureRank(extraType: String?): Int = FEATURE_TYPE_ORDER.indexOf(extraType).let { if (it < 0) FEATURE_TYPE_ORDER.size else it }

/**
 * docs/19 §1.3: trailers (server order, then openable links), then features by type and name. A
 * Movie drops the trailer its pill plays; theme songs are audio and never listed.
 */
fun buildExtrasList(
    itemType: String,
    localTrailers: List<ExtraItem>,
    remote: List<RemoteTrailer>,
    features: List<ExtraItem>,
    canOpenRemote: (String) -> Boolean,
): List<ExtraEntry> {
    // The endpoint is the trailer list, so an untyped entry is a trailer.
    val trailers = localTrailers.map { ExtraEntry.Local(it.copy(extraType = it.extraType ?: TRAILER_EXTRA_TYPE)) } +
        remote.filter { canOpenRemote(it.url) }.map { ExtraEntry.Remote(it.name, it.url) }
    val shownTrailers = if (itemType == MOVIE_ITEM_TYPE) trailers.drop(1) else trailers
    val shownFeatures = features
        .filter { it.extraType != THEME_SONG_EXTRA_TYPE }
        .sortedWith(compareBy<ExtraItem>({ featureRank(it.extraType) }, { it.name.lowercase() }))
        .map { ExtraEntry.Local(it) }
    return shownTrailers + shownFeatures
}

/** One rendered row of the extras level: the server's [label] verbatim (a blank one falls back) and a state-column [subtext]. */
data class ExtraRow(val entry: ExtraEntry, val label: String, val subtext: String)

/** Pure so [DetailMenuModelTest] covers it without Compose; the panel calls this directly. */
fun buildExtraRows(strings: UiStrings, entries: List<ExtraEntry>): List<ExtraRow> = entries.map { entry ->
    when (entry) {
        is ExtraEntry.Local -> ExtraRow(
            entry,
            entry.item.name.ifBlank {
                strings.get(if (entry.item.extraType == TRAILER_EXTRA_TYPE) R.string.detail_extra_trailer_fallback else R.string.detail_extra_fallback)
            },
            strings.get(localTypeLabelRes(entry.item.extraType)),
        )
        is ExtraEntry.Remote -> ExtraRow(
            entry,
            entry.name?.takeIf { it.isNotBlank() } ?: strings.get(R.string.detail_extra_trailer_fallback),
            strings.get(if (youtubeKey(entry.url) != null) R.string.detail_extra_type_youtube else R.string.detail_extra_type_trailer),
        )
    }
}

private fun localTypeLabelRes(extraType: String?): Int = when (extraType) {
    TRAILER_EXTRA_TYPE -> R.string.detail_extra_type_trailer
    "BehindTheScenes" -> R.string.detail_extra_type_behind_the_scenes
    "Featurette" -> R.string.detail_extra_type_featurette
    "Interview" -> R.string.detail_extra_type_interview
    "Scene" -> R.string.detail_extra_type_scene
    "DeletedScene" -> R.string.detail_extra_type_deleted_scene
    "Short" -> R.string.detail_extra_type_short
    "Clip" -> R.string.detail_extra_type_clip
    "Sample" -> R.string.detail_extra_type_sample
    else -> R.string.detail_extra_type_other
}

/** docs/19 §1.1's random pick: uniform over non-virtual episodes, `null` if none. */
fun pickRandom(episodes: List<Card>, random: Random): Card? =
    episodes.filterNot { it.isVirtual }.randomOrNull(random)
