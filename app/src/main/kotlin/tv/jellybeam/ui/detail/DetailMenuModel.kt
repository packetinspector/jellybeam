package tv.jellybeam.ui.detail

import kotlin.random.Random
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.CollectionInfo

/**
 * Pure, plain-Kotlin content model for the detail-page action panel (docs/19-detail-action-menu.md
 * §1/§3.2), same discipline as [DetailFormatting]. [buildMenu] evaluates every presence rule in
 * §1.1; the Compose layer ([DetailActionPanel]) only renders [MenuModel] and dispatches
 * [MenuAction] to [DetailViewModel.runAction]. Labels are plain literal text (no `stringResource`
 * in pure Kotlin); fixed chrome lives in `strings.xml` instead.
 */

private const val SERIES_ITEM_TYPE = "Series"
private const val EPISODE_ITEM_TYPE = "Episode"

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

    data class GoToSeries(val series: Card) : MenuAction
    data object AddToCollection : MenuAction
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
)

/** docs/19 §1.1/§1.2: builds every group/row this input warrants, and which row focuses first. */
fun buildMenu(input: MenuInput): MenuModel {
    val thisTitleRows = buildThisTitleRows(input)
    val playbackRows = buildPlaybackRows(input)
    val libraryRows = buildLibraryRows(input)

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
        else -> when {
            hasProgress -> firstRowMatching { it is MenuAction.PlayFromBeginning }
            !input.card.played -> MenuAction.MarkWatched
            else -> null
        }
    }

    return MenuModel(groups, focusOn)
}

private fun buildThisTitleRows(input: MenuInput): List<MenuRow> {
    val rows = mutableListOf<MenuRow>()
    if (input.itemType == SERIES_ITEM_TYPE) {
        val isSeason = input.scopeSeason != null
        val scopeEpisodes = (if (isSeason) input.scopeEpisodes else input.allEpisodes).filterNot { it.isVirtual }
        val unwatchedCount = scopeEpisodes.count { !it.played }
        val watchedCount = scopeEpisodes.count { it.played }
        if (unwatchedCount > 0) {
            rows += MenuRow(
                action = MenuAction.MarkScopeWatched(unwatchedCount, isSeason),
                label = if (isSeason) "Mark season as watched" else "Mark series as watched",
                subtext = "$unwatchedCount EPISODES",
            )
        }
        if (watchedCount > 0) {
            rows += MenuRow(
                action = MenuAction.MarkScopeUnwatched(watchedCount, isSeason),
                label = if (isSeason) "Mark season as unwatched" else "Mark series as unwatched",
                subtext = "$watchedCount EPISODES",
            )
        }
    } else {
        rows += if (input.card.played) {
            MenuRow(MenuAction.MarkUnwatched, "Mark as unwatched", null)
        } else {
            // §1.1: `NEVER PLAYED` when there's also no saved position.
            val neverPlayed = input.card.positionTicks <= 0
            MenuRow(MenuAction.MarkWatched, "Mark as watched", if (neverPlayed) "NEVER PLAYED" else null)
        }
    }
    // §1.1: this row reads/writes the series flag even in season scope; `SERIES` marks that in the
    // state column.
    val isSeasonScope = input.itemType == SERIES_ITEM_TYPE && input.scopeSeason != null
    rows += if (input.isFavorite) {
        MenuRow(MenuAction.RemoveFavorite, "Remove from favorites", if (isSeasonScope) "SERIES" else null)
    } else {
        MenuRow(MenuAction.AddFavorite, "Add to favorites", if (isSeasonScope) "SERIES" else null)
    }
    return rows
}

private fun buildPlaybackRows(input: MenuInput): List<MenuRow> {
    val rows = mutableListOf<MenuRow>()
    if (input.itemType == SERIES_ITEM_TYPE) {
        val isSeason = input.scopeSeason != null
        val scopeEpisodes = (if (isSeason) input.scopeEpisodes else input.allEpisodes).filterNot { it.isVirtual }
        if (isSeason) {
            val target = scopeEpisodes.firstOrNull { it.positionTicks > 0 } ?: scopeEpisodes.firstOrNull { !it.played }
            if (target != null) {
                val subtext = playNextUnwatchedSubtext(target, input.scopeSeason?.indexNumber)
                rows += MenuRow(MenuAction.PlayNextUnwatched(target.id, subtext), "Play next unwatched", subtext)
            }
        } else {
            val action = input.primaryAction
            if (action is DetailFormatting.PrimaryAction.Playable) {
                val target = input.allEpisodes.firstOrNull { it.id == action.targetId }
                val subtext = target?.let { playNextUnwatchedSubtext(it, input.highlightedSeasonNumber) }
                rows += MenuRow(MenuAction.PlayNextUnwatched(action.targetId, subtext), "Play next unwatched", subtext)
                if (action.hasProgress) {
                    rows += MenuRow(MenuAction.PlayFromBeginning(action.targetId), "Play from the beginning", null)
                }
            }
        }
        if (scopeEpisodes.isNotEmpty()) {
            rows += MenuRow(
                action = MenuAction.PlayRandom(scopeEpisodes.size),
                label = "Play something random",
                subtext = "FROM ${scopeEpisodes.size}",
            )
        }
    } else {
        val action = input.primaryAction
        if (action is DetailFormatting.PrimaryAction.Playable && action.hasProgress) {
            rows += MenuRow(MenuAction.PlayFromBeginning(action.targetId), "Play from the beginning", null)
        }
    }
    return rows
}

/**
 * §1.1: `E{n}` in the highlighted season, else `S{s} E{n}`; `null` if a number is missing, never
 * a partial `S? E?` (same rule as [DetailFormatting]'s `strictSeasonEpisode`).
 */
private fun playNextUnwatchedSubtext(target: Card, highlightedSeasonNumber: Int?): String? {
    val episodeNumber = target.indexNumber ?: return null
    if (highlightedSeasonNumber != null && target.parentIndexNumber == highlightedSeasonNumber) return "E$episodeNumber"
    val seasonNumber = target.parentIndexNumber ?: return null
    return "S$seasonNumber E$episodeNumber"
}

private fun buildLibraryRows(input: MenuInput): List<MenuRow> {
    val rows = mutableListOf<MenuRow>()
    if (input.itemType == EPISODE_ITEM_TYPE && input.seriesCard != null) {
        // Verbatim server name, never re-cased (CLAUDE.md hard rule).
        rows += MenuRow(MenuAction.GoToSeries(input.seriesCard), "Go to series", input.seriesCard.name)
    }
    val isSeasonScope = input.itemType == SERIES_ITEM_TYPE && input.scopeSeason != null
    if (!isSeasonScope && input.hasCollections) {
        rows += MenuRow(MenuAction.AddToCollection, "Add to collection", null)
    }
    if (input.isAdministrator) {
        // §1.1: always acts on the whole series, so season scope marks it `SERIES` too.
        rows += MenuRow(MenuAction.RefreshMetadata, "Refresh metadata", if (isSeasonScope) "SERIES" else null)
    }
    return rows
}

/** docs/19 §1.3: one row per collection, server order; `ALREADY IN` rows aren't focusable. */
data class CollectionRow(val collection: CollectionInfo, val alreadyIn: Boolean)

/** Pure so [DetailMenuModelTest] covers it without Compose; the panel calls this directly. */
fun buildCollectionRows(collections: List<CollectionInfo>, memberOfCollections: Set<String>): List<CollectionRow> =
    collections.map { CollectionRow(it, alreadyIn = it.id in memberOfCollections) }

/** docs/19 §1.1's random pick: uniform over non-virtual episodes, `null` if none. */
fun pickRandom(episodes: List<Card>, random: Random): Card? =
    episodes.filterNot { it.isVirtual }.randomOrNull(random)
