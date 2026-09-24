package tv.jellybeam.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import uniffi.jellybeam_core.CollectionInfo

// docs/19 §1.5/§3.4: the door, panel, and toast -- rendered from a root Box in [DetailScreen].

/** Not `private`: [DetailScreen]'s own close-restore effect places focus back by this same key. */
internal const val MENU_DOOR_KEY = "action:menu"

// -- Panel geometry/colour (docs/19 §1.5) ------

internal val PANEL_WIDTH = 330.dp
private val PANEL_PADDING_TOP = 42.dp
private val PANEL_PADDING_BOTTOM = 28.dp
private val PANEL_SIDE_PADDING = 28.dp
private val PANEL_FOCUSED_INSET = 10.dp
private val PANEL_FOCUSED_HPADDING = 18.dp
private val PANEL_SHADOW_WIDTH = 70.dp
private const val PANEL_ANIMATION_MS = 250

/** `#100D0B` -- panel ground; no [JellybeamTheme] token matches. */
private val PanelGround = Color(0xFF100D0B)

/** `#6B6157` -- unfocused row/heading-label state text. */
private val PanelMuted = Color(0xFF6B6157)

/** `#2E3A18` -- focused row's subtext colour (not the label's Notte). */
private val PanelFocusedSubtext = Color(0xFF2E3A18)

/** `rgba(7,6,5,0.9)` -- the shadow gradient's edge stop, adjacent to the panel. */
private val PanelShadowEdge = Color(0xFF070605).copy(alpha = 0.9f)

/** §1 rule 1: the round `···` door pill, second on every page, right of the primary pill. */
@Composable
internal fun MenuDoorPill(
    onOpen: () -> Unit,
    memory: FocusMemory,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(modifier = modifier.focusRing(isFocused = isFocused, cornerRadius = ACTION_BUTTON_HEIGHT / 2, color = JellybeamTheme.Sheen)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .focusKey(memory, MENU_DOOR_KEY)
                .size(ACTION_BUTTON_HEIGHT)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .clip(CircleShape)
                .background(JellybeamTheme.Pistacchio)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen),
        ) {
            BasicText(
                text = "···",
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Notte, fontSize = 15.sp),
            )
        }
    }
}

private fun groupLabelRes(group: MenuGroup): Int = when (group) {
    MenuGroup.THIS_TITLE -> R.string.detail_menu_group_this_title
    MenuGroup.PLAYBACK -> R.string.detail_menu_group_playback
    MenuGroup.LIBRARY -> R.string.detail_menu_group_library
}

/**
 * §1.5's panel: three always-open groups, pinned to the right edge at [PANEL_WIDTH], sliding on
 * [offsetX] (animated by [DetailScreen] since the exit animation must outlive [menu] going
 * `null`). Traps focus, seeds [MenuModel.focusOn] (else row 0), and remembers the last first-level
 * row index so Back/Cancel return to the opening row. A collections level with no addable row
 * seeds an invisible anchor (docs/19 §1.3) and scrolls (up to 200); the first level never does.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DetailActionPanel(
    menu: MenuUiState,
    itemName: String,
    collections: List<CollectionInfo>,
    memberOfCollections: Set<String>,
    confirm: BulkMarkConfirm?,
    offsetX: Dp,
    focusGate: MutableState<Boolean>,
    isTop: Boolean,
    onRunAction: (MenuAction) -> Unit,
    onEnterCollections: () -> Unit,
    onSelectCollection: (CollectionInfo) -> Unit,
    onBackFromCollections: () -> Unit,
    onConfirmBulkMark: () -> Unit,
    onDismissConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = menu.level
    val firstLevelRows = remember(menu.model) { menu.model.groups.flatMap { it.rows } }
    val collectionRows = remember(collections, memberOfCollections) { buildCollectionRows(collections, memberOfCollections) }

    // One requester per row; a collections row already in gets `null` so Up/Down skips it.
    val firstLevelRequesters = remember(firstLevelRows.size) { List(firstLevelRows.size) { FocusRequester() } }
    val collectionRequesters = remember(collectionRows) { collectionRows.map { if (!it.alreadyIn) FocusRequester() else null } }
    // docs/19 §1.3: an invisible anchor for when every row is `ALREADY IN`, so the trap still has a
    // focused node.
    val collectionsAnchorRequester = remember { FocusRequester() }
    val confirmCancelRequester = remember(confirm != null) { FocusRequester() }

    var lastFirstLevelIndex by remember(menu.model) {
        val seed = firstLevelRows.indexOfFirst { it.action == menu.model.focusOn }.let { if (it >= 0) it else 0 }
        mutableIntStateOf(seed)
    }

    // Seeds focus on open, on a level change, and once confirm clears; keyed on [isTop] so a hidden
    // panel re-seeds only when top again.
    LaunchedEffect(level, confirm != null, firstLevelRequesters, collectionRequesters, isTop) {
        if (!isTop || confirm != null) return@LaunchedEffect
        when (level) {
            MenuLevel.FIRST -> if (firstLevelRequesters.isNotEmpty()) {
                val index = lastFirstLevelIndex.coerceIn(0, firstLevelRequesters.lastIndex)
                requestFocusUntilSuccess(focusGate) { firstLevelRequesters[index].requestFocus() }
            }
            MenuLevel.COLLECTIONS -> {
                val target = collectionRequesters.firstOrNull { it != null }
                    ?: collectionsAnchorRequester.takeIf { collectionRequesters.none { it != null } }
                if (target != null) requestFocusUntilSuccess(focusGate) { target.requestFocus() }
            }
        }
    }
    // docs/19 §1.3: Cancel seeds focus first (safety-over-speed convention for a two-button
    // confirm).
    LaunchedEffect(confirm != null, isTop) {
        if (confirm != null && isTop) requestFocusUntilSuccess(focusGate) { confirmCancelRequester.requestFocus() }
    }

    // §1.5: Back is context-sensitive (confirm cancels; collections goes up; else closes); Left
    // closes except under the confirm. Gated on [isTop].
    BackHandler(enabled = isTop) {
        when {
            confirm != null -> onDismissConfirm()
            level == MenuLevel.COLLECTIONS -> onBackFromCollections()
            else -> onClose()
        }
    }

    Row(modifier = modifier.fillMaxHeight().offset(x = offsetX)) {
        // §1.5's leftward shadow: a horizontal gradient band in the same Row, tracking the panel's
        // slide.
        Box(
            modifier = Modifier
                .width(PANEL_SHADOW_WIDTH)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, PanelShadowEdge))),
        )
        Box(
            modifier = Modifier
                .width(PANEL_WIDTH)
                .fillMaxHeight()
                .background(PanelGround)
                .drawBehind {
                    drawLine(JellybeamTheme.Hairline, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 0.5.dp.toPx())
                }
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // docs/19 §1.3: the confirm's pill pair is horizontal, so while it is up
                    // Left/Right walk the pills (exit = Cancel keeps focus in the panel) and only
                    // Back or Cancel leaves; otherwise §1.5 Left closes, Right is consumed.
                    when (event.key) {
                        Key.DirectionLeft -> if (confirm != null) false else { onClose(); true }
                        Key.DirectionRight -> confirm == null
                        else -> false
                    }
                },
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = PANEL_PADDING_TOP, bottom = PANEL_PADDING_BOTTOM),
            ) {
                PanelHeader(itemName = itemName, seasonNumber = menu.scopeSeasonNumber, level = level)
                Box(Modifier.height(15.dp))
                AnimatedContent(
                    targetState = level,
                    transitionSpec = {
                        // Entering collections slides in from the right; Back reverses it.
                        if (targetState == MenuLevel.COLLECTIONS) {
                            (slideInHorizontally(tween(PANEL_ANIMATION_MS)) { width -> width } + fadeIn(tween(PANEL_ANIMATION_MS))) togetherWith
                                (slideOutHorizontally(tween(PANEL_ANIMATION_MS)) { width -> -width } + fadeOut(tween(PANEL_ANIMATION_MS)))
                        } else {
                            (slideInHorizontally(tween(PANEL_ANIMATION_MS)) { width -> -width } + fadeIn(tween(PANEL_ANIMATION_MS))) togetherWith
                                (slideOutHorizontally(tween(PANEL_ANIMATION_MS)) { width -> width } + fadeOut(tween(PANEL_ANIMATION_MS)))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = "panel-rows",
                ) { animatedLevel ->
                    when (animatedLevel) {
                        MenuLevel.FIRST -> Column {
                            var flatIndex = 0
                            menu.model.groups.forEachIndexed { groupIndex, groupModel ->
                                HeadingBand(label = stringResource(groupLabelRes(groupModel.group)).uppercase(Locale.US), isFirst = groupIndex == 0)
                                groupModel.rows.forEach { row ->
                                    val index = flatIndex++
                                    if (confirm != null && row.action == confirm.action) {
                                        BulkConfirmBlock(
                                            confirm = confirm,
                                            cancelFocusRequester = confirmCancelRequester,
                                            onConfirm = onConfirmBulkMark,
                                            onCancel = onDismissConfirm,
                                        )
                                    } else {
                                        PanelActionRow(
                                            label = row.label,
                                            subtext = row.subtext,
                                            drillIn = row.action == MenuAction.AddToCollection,
                                            dimmed = confirm != null,
                                            focusable = confirm == null,
                                            focusRequester = if (confirm == null) firstLevelRequesters.getOrNull(index) else null,
                                            onSelect = {
                                                lastFirstLevelIndex = index
                                                if (row.action == MenuAction.AddToCollection) onEnterCollections() else onRunAction(row.action)
                                            },
                                            onFocusChanged = { focused -> if (focused) lastFirstLevelIndex = index },
                                        )
                                    }
                                }
                            }
                        }
                        // docs/19 §1.3/§1.5: unlike the first level, this one scrolls (up to 200
                        // rows); `PanelActionRow`'s `clickable` brings a focused row into view.
                        MenuLevel.COLLECTIONS -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            HeadingBand(label = stringResource(R.string.detail_menu_collections_count, collections.size).uppercase(Locale.US), isFirst = true)
                            if (collectionRequesters.none { it != null }) {
                                Box(Modifier.size(1.dp).focusRequester(collectionsAnchorRequester).focusable())
                            }
                            val alreadyInLabel = stringResource(R.string.detail_menu_already_in).uppercase(Locale.US)
                            collectionRows.forEachIndexed { index, row ->
                                PanelActionRow(
                                    label = row.collection.name,
                                    subtext = if (row.alreadyIn) alreadyInLabel else null,
                                    focusable = !row.alreadyIn,
                                    focusRequester = collectionRequesters.getOrNull(index),
                                    onSelect = { onSelectCollection(row.collection) },
                                )
                            }
                        }
                    }
                }
                PanelFooter(text = footerText(level, confirmVisible = confirm != null, actionRowCount = firstLevelRows.size))
            }
        }
    }
}

/** §1.5's header: `ACTIONS [│ SEASON N]` kicker (or `◂ ADD TO COLLECTION` on collections), then
 * the item name -- 2 lines max, always the item's own name (never the season's).
 */
@Composable
private fun PanelHeader(itemName: String, seasonNumber: Int?, level: MenuLevel) {
    val actionsLabel = stringResource(R.string.detail_menu_kicker_actions).uppercase(Locale.US)
    val seasonLabel = seasonNumber?.let { stringResource(R.string.detail_menu_kicker_season, it).uppercase(Locale.US) }
    val collectionsLabel = stringResource(R.string.detail_menu_collections_header).uppercase(Locale.US)
    val kicker = remember(level, actionsLabel, seasonLabel, collectionsLabel) {
        buildAnnotatedString {
            if (level == MenuLevel.COLLECTIONS) {
                withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append("◂ ") }
                withStyle(SpanStyle(color = JellybeamTheme.Pistacchio)) { append(collectionsLabel) }
            } else {
                withStyle(SpanStyle(color = JellybeamTheme.Pistacchio)) { append(actionsLabel) }
                if (seasonLabel != null) {
                    withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(" │ ") }
                    withStyle(SpanStyle(color = JellybeamTheme.Grigio)) { append(seasonLabel) }
                }
            }
        }
    }
    Column(modifier = Modifier.padding(horizontal = PANEL_SIDE_PADDING)) {
        BasicText(text = kicker, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, fontSize = 9.sp, letterSpacing = (-0.02).em))
        Box(Modifier.height(8.dp))
        BasicText(
            text = itemName,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 15.sp),
        )
    }
}

/** §1.5's 0.5dp full-bleed rule (deliberately NOT inset, unlike other panel elements), then the
 * mono uppercase label; a group with no rows never calls this (caller iterates non-empty groups).
 */
@Composable
private fun HeadingBand(label: String, isFirst: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = if (isFirst) 0.dp else 10.dp)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(JellybeamTheme.Hairline))
        Box(Modifier.height(11.dp))
        BasicText(
            text = label,
            modifier = Modifier.padding(horizontal = PANEL_SIDE_PADDING),
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, fontSize = 8.sp, color = PanelMuted, letterSpacing = (-0.02).em),
        )
        Box(Modifier.height(4.dp))
    }
}

/** §1.5's action row: unfocused inset [PANEL_SIDE_PADDING]; focused is a pill inset
 * [PANEL_FOCUSED_INSET] + [PANEL_FOCUSED_HPADDING] (10+18=28dp either way). [dimmed]/[focusable]
 * come from bulk-confirm (docs/19 §1.3); [drillIn] draws `▸` instead of [subtext].
 */
@Composable
private fun PanelActionRow(
    label: String,
    subtext: String?,
    drillIn: Boolean = false,
    dimmed: Boolean = false,
    focusable: Boolean = true,
    focusRequester: FocusRequester? = null,
    onSelect: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    var rowModifier: Modifier = Modifier
    if (focusable && focusRequester != null) rowModifier = rowModifier.focusRequester(focusRequester)
    if (focusable) rowModifier = rowModifier.onFocusChanged { onFocusChanged(it.isFocused) }
    rowModifier = rowModifier
        .fillMaxWidth()
        .padding(horizontal = if (isFocused) PANEL_FOCUSED_INSET else PANEL_SIDE_PADDING)
        .alpha(if (dimmed) 0.4f else 1f)
    if (isFocused) rowModifier = rowModifier.clip(RoundedCornerShape(50)).background(JellybeamTheme.Pistacchio)
    if (focusable) rowModifier = rowModifier.clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
    rowModifier = rowModifier.padding(horizontal = if (isFocused) PANEL_FOCUSED_HPADDING else 0.dp, vertical = 7.dp)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isFocused) JellybeamTheme.Notte else JellybeamTheme.Panna2,
                fontSize = 13.sp,
            ),
        )
        if (drillIn) {
            BasicText(text = "▸", style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.HairlineStrong, fontSize = 8.sp))
        } else if (subtext != null) {
            BasicText(
                text = subtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = JellybeamTheme.MartianMono,
                    color = if (isFocused) PanelFocusedSubtext else PanelMuted,
                    fontSize = 8.sp,
                    letterSpacing = (-0.02).em,
                ),
            )
        }
    }
}

/** §1.5's footer: `BACK TO CLOSE │ N ACTIONS` (first level), `BACK FOR ACTIONS` (collections), or
 * `BACK TO CANCEL` (confirm) -- the `│` its own dim colour, distinct from the text either side.
 */
@Composable
private fun footerText(level: MenuLevel, confirmVisible: Boolean, actionRowCount: Int): AnnotatedString {
    val backToCancel = stringResource(R.string.detail_menu_footer_back_to_cancel).uppercase(Locale.US)
    val backForActions = stringResource(R.string.detail_menu_footer_back_for_actions).uppercase(Locale.US)
    val backToClose = stringResource(R.string.detail_menu_footer_close).uppercase(Locale.US)
    val actionsCount = stringResource(R.string.detail_menu_footer_actions_count, actionRowCount).uppercase(Locale.US)
    return remember(level, confirmVisible, actionRowCount, backToCancel, backForActions, backToClose, actionsCount) {
        buildAnnotatedString {
            when {
                confirmVisible -> withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(backToCancel) }
                level == MenuLevel.COLLECTIONS -> withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(backForActions) }
                else -> {
                    withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(backToClose) }
                    withStyle(SpanStyle(color = JellybeamTheme.Surface)) { append("  │  ") }
                    withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(actionsCount) }
                }
            }
        }
    }
}

@Composable
private fun PanelFooter(text: AnnotatedString) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = PANEL_SIDE_PADDING)) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(JellybeamTheme.Surface))
        }
        Box(Modifier.height(12.dp))
        BasicText(
            text = text,
            modifier = Modifier.padding(horizontal = PANEL_SIDE_PADDING),
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, fontSize = 8.sp, letterSpacing = (-0.02).em),
        )
    }
}

/** §1.3's in-place bulk-mark confirm: replaces the triggering row (other rows dimmed by the
 * caller); Cancel seeds focus first (safety-over-speed); Back ([BackHandler]) also cancels.
 */
@Composable
private fun BulkConfirmBlock(
    confirm: BulkMarkConfirm,
    cancelFocusRequester: FocusRequester,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = if (confirm.played) {
        stringResource(R.string.detail_menu_confirm_watched_title, confirm.count)
    } else {
        stringResource(R.string.detail_menu_confirm_unwatched_title, confirm.count)
    }
    val body = if (confirm.played) {
        stringResource(R.string.detail_menu_confirm_watched_body, confirm.scopeName)
    } else {
        stringResource(R.string.detail_menu_confirm_unwatched_body, confirm.scopeName)
    }
    val confirmLabel = stringResource(
        if (confirm.played) R.string.detail_menu_confirm_watched_button else R.string.detail_menu_confirm_unwatched_button,
    )
    val cancelLabel = stringResource(R.string.detail_menu_confirm_cancel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PANEL_FOCUSED_INSET)
            .clip(RoundedCornerShape(7.dp))
            .background(JellybeamTheme.Notte)
            .border(1.dp, JellybeamTheme.Pistacchio, RoundedCornerShape(7.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(text = title, style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 13.5.sp))
        BasicText(text = body, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 10.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConfirmPillButton(label = confirmLabel, primary = true, onClick = onConfirm)
            ConfirmPillButton(label = cancelLabel, primary = false, onClick = onCancel, focusRequester = cancelFocusRequester)
        }
    }
}

@Composable
private fun ConfirmPillButton(label: String, primary: Boolean, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val ringColor = if (primary) JellybeamTheme.Sheen else JellybeamTheme.Pistacchio

    Box(modifier = Modifier.focusRing(isFocused = isFocused, cornerRadius = 999.dp, color = ringColor)) {
        Box(
            modifier = Modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .clip(RoundedCornerShape(50))
                .background(if (primary) JellybeamTheme.Pistacchio else Color.Transparent)
                .let { if (!primary) it.border(1.dp, JellybeamTheme.HairlineStrong, RoundedCornerShape(50)) else it }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    fontWeight = FontWeight.SemiBold,
                    color = if (primary) JellybeamTheme.Notte else JellybeamTheme.Panna,
                    fontSize = 11.5.sp,
                ),
            )
        }
    }
}

/** §1.4's toast: `✓` + text, Surface fill, 1dp Hairline border, 8dp radius (caller positions). */
@Composable
fun DetailMenuToast(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(JellybeamTheme.Surface)
            .border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(text = "✓", style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp))
        BasicText(text = text, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp))
    }
}
