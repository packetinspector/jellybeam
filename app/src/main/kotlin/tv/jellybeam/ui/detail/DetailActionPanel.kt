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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
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
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import tv.jellybeam.ui.music.BlocksAmbient
import tv.jellybeam.ui.music.LoadingDots
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

/** docs/29 §8.7: the 28 px ✓ gutter. */
private val PANEL_GUTTER = 14.dp
private const val PANEL_ANIMATION_MS = 250

/** `#100D0B` -- panel ground; no [JellybeamTheme] token matches. */
private val PanelGround = Color(0xFF100D0B)

/** `#6B6157` -- unfocused row/heading-label state text. */
private val PanelMuted = Color(0xFF6B6157)

/** `rgba(7,6,5,0.9)` -- the shadow gradient's edge stop, adjacent to the panel. */
private val PanelShadowEdge = Color(0xFF070605).copy(alpha = 0.9f)

/** §1 rule 1: the round `···` door pill, second on every page, right of the primary pill. */
@Composable
internal fun MenuDoorPill(
    onOpen: () -> Unit,
    memory: FocusMemory,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** Matches a taller primary pill beside it (the collection page's two-line Play). */
    size: Dp = ACTION_BUTTON_HEIGHT,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accent = LocalAccent.current

    Box(modifier = modifier.focusRing(isFocused = isFocused, cornerRadius = size / 2, color = accent.accentSheen)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .focusKey(memory, MENU_DOOR_KEY)
                .size(size)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .clip(CircleShape)
                .background(accent.accent)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onOpen),
        ) {
            BasicText(
                text = "···",
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = accent.onAccent, fontSize = 15.sp),
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
 * seeds an invisible anchor (docs/19 §1.3) and scrolls (up to 200); the first level never does. The
 * extras level is the same shape: its heading reads `LOADING` or the failure text until rows land.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DetailActionPanel(
    menu: MenuUiState,
    itemName: String,
    collections: List<CollectionInfo>,
    memberOfCollections: Set<String>,
    extras: ExtrasState,
    strings: UiStrings,
    confirm: BulkMarkConfirm?,
    offsetX: Dp,
    isTop: Boolean,
    onRunAction: (MenuAction) -> Unit,
    onEnterCollections: () -> Unit,
    onSelectCollection: (CollectionInfo) -> Unit,
    onBackFromCollections: () -> Unit,
    onSelectExtra: (ExtraEntry) -> Unit,
    onBackFromExtras: () -> Unit,
    onConfirmBulkMark: () -> Unit,
    onDismissConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BlocksAmbient()
    val focusGate = LocalFocusGate.current
    val level = menu.level
    val firstLevelRows = remember(menu.model) { menu.model.groups.flatMap { it.rows } }
    val collectionRows = remember(collections, memberOfCollections) { buildCollectionRows(collections, memberOfCollections) }
    val extraRows = remember(extras, strings) { (extras as? ExtrasState.Loaded)?.let { buildExtraRows(strings, it.entries) }.orEmpty() }

    // One requester per row; a collections row already in gets `null` so Up/Down skips it.
    val firstLevelRequesters = remember(firstLevelRows.size) { List(firstLevelRows.size) { FocusRequester() } }
    val collectionRequesters = remember(collectionRows) { collectionRows.map { if (!it.alreadyIn) FocusRequester() else null } }
    val extraRequesters = remember(extraRows) { extraRows.map { FocusRequester() } }
    // docs/19 §1.3: an invisible anchor for when every collection is `ALREADY IN` or the extras are
    // still loading, so the trap still has a focused node.
    val secondLevelAnchor = remember { FocusRequester() }
    val confirmCancelRequester = remember(confirm != null) { FocusRequester() }

    var lastFirstLevelIndex by remember(menu.model) {
        val seed = firstLevelRows.indexOfFirst { it.action == menu.model.focusOn }.let { if (it >= 0) it else 0 }
        mutableIntStateOf(seed)
    }

    // Seeds focus on open, on a level change, and once confirm clears; keyed on [isTop] so a hidden
    // panel re-seeds only when top again. A level's rows key it only while that level is showing, so
    // late rows never pull focus off another level.
    LaunchedEffect(
        level,
        confirm != null,
        firstLevelRequesters,
        collectionRequesters.takeIf { level == MenuLevel.COLLECTIONS },
        extraRequesters.takeIf { level == MenuLevel.EXTRAS },
        isTop,
    ) {
        if (!isTop || confirm != null) return@LaunchedEffect
        when (level) {
            MenuLevel.FIRST -> if (firstLevelRequesters.isNotEmpty()) {
                val index = lastFirstLevelIndex.coerceIn(0, firstLevelRequesters.lastIndex)
                requestFocusUntilSuccess(focusGate) { firstLevelRequesters[index].requestFocus() }
            }
            MenuLevel.COLLECTIONS -> {
                val target = collectionRequesters.firstOrNull { it != null }
                    ?: secondLevelAnchor.takeIf { collectionRequesters.none { it != null } }
                if (target != null) requestFocusUntilSuccess(focusGate) { target.requestFocus() }
            }
            MenuLevel.EXTRAS -> {
                val target = extraRequesters.firstOrNull() ?: secondLevelAnchor
                requestFocusUntilSuccess(focusGate) { target.requestFocus() }
            }
        }
    }
    // docs/19 §1.3: Cancel seeds focus first (safety-over-speed convention for a two-button
    // confirm).
    LaunchedEffect(confirm != null, isTop) {
        if (confirm != null && isTop) requestFocusUntilSuccess(focusGate) { confirmCancelRequester.requestFocus() }
    }

    // §1.5: Back is context-sensitive (confirm cancels; a second level goes up; else closes); Left
    // closes except under the confirm. Gated on [isTop].
    BackHandler(enabled = isTop) {
        when {
            confirm != null -> onDismissConfirm()
            level == MenuLevel.COLLECTIONS -> onBackFromCollections()
            level == MenuLevel.EXTRAS -> onBackFromExtras()
            else -> onClose()
        }
    }

    ActionPanelFrame(
        offsetX = offsetX,
        modifier = modifier,
        onPreviewKey = { event ->
            if (event.type != KeyEventType.KeyDown) return@ActionPanelFrame false
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
                PanelHeader(itemName = itemName, seasonNumber = menu.scopeSeasonNumber, level = level)
                Box(Modifier.height(15.dp))
                AnimatedContent(
                    targetState = level,
                    transitionSpec = {
                        // Entering a second level slides in from the right; Back reverses it.
                        if (targetState != MenuLevel.FIRST) {
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
                                HeadingBand(label = stringResource(groupLabelRes(groupModel.group)).uppercaseUi(), isFirst = groupIndex == 0)
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
                                            drillIn = row.action == MenuAction.AddToCollection || row.action == MenuAction.Extras,
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
                        MenuLevel.COLLECTIONS -> SecondLevelColumn(
                            heading = pluralStringResource(R.plurals.detail_menu_collections_count, collections.size, collections.size).uppercaseUi(),
                            anchor = secondLevelAnchor.takeIf { collectionRequesters.none { it != null } },
                        ) {
                            val alreadyInLabel = stringResource(R.string.detail_menu_already_in).uppercaseUi()
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
                        MenuLevel.EXTRAS -> SecondLevelColumn(
                            heading = when (extras) {
                                is ExtrasState.Loaded -> pluralStringResource(R.plurals.detail_menu_extras_count, extraRows.size, extraRows.size)
                                ExtrasState.Failed -> stringResource(R.string.detail_menu_extras_failed)
                                ExtrasState.Idle, ExtrasState.Loading -> stringResource(R.string.detail_menu_extras_loading)
                            }.uppercaseUi(),
                            anchor = secondLevelAnchor.takeIf { extraRows.isEmpty() },
                        ) {
                            extraRows.forEachIndexed { index, row ->
                                PanelActionRow(
                                    label = row.label,
                                    subtext = row.subtext,
                                    focusRequester = extraRequesters.getOrNull(index),
                                    onSelect = { onSelectExtra(row.entry) },
                                )
                            }
                        }
                    }
                }
                PanelFooter(text = footerText(level, confirmVisible = confirm != null, actionRowCount = firstLevelRows.size))
    }
}

/**
 * §1.5's panel chrome, shared with the music menu (docs/29 §8.7): the leftward shadow, the ground with
 * its hairline, the focus trap and [onPreviewKey], sliding on [offsetX]; [content] is the padded column.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ActionPanelFrame(
    offsetX: Dp,
    onPreviewKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
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
                .onPreviewKeyEvent(onPreviewKey),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = PANEL_PADDING_TOP, bottom = PANEL_PADDING_BOTTOM),
                content = content,
            )
        }
    }
}

/**
 * docs/19 §1.3/§1.5: a second level's body. Unlike the first level it scrolls (up to 200 rows);
 * `PanelActionRow`'s `clickable` brings a focused row into view. [anchor] is the invisible focus
 * node for a level with nothing focusable yet.
 */
@Composable
private fun SecondLevelColumn(heading: String, anchor: FocusRequester?, rows: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        HeadingBand(label = heading, isFirst = true)
        if (anchor != null) Box(Modifier.size(1.dp).focusRequester(anchor).focusable())
        rows()
    }
}

/** §1.5's header: `ACTIONS [│ SEASON N]` kicker (or `◂ ADD TO COLLECTION` / `◂ TRAILERS & EXTRAS` on a second level), then
 * the item name -- 2 lines max, always the item's own name (never the season's).
 */
@Composable
private fun PanelHeader(itemName: String, seasonNumber: Int?, level: MenuLevel) {
    val actionsLabel = stringResource(R.string.detail_menu_kicker_actions).uppercaseUi()
    val seasonLabel = seasonNumber?.let { stringResource(R.string.detail_menu_kicker_season, it).uppercaseUi() }
    val collectionsLabel = stringResource(R.string.detail_menu_collections_header).uppercaseUi()
    val extrasLabel = stringResource(R.string.detail_menu_extras).uppercaseUi()
    val ink = LocalAccent.current.accentInk
    val kicker = remember(level, actionsLabel, seasonLabel, collectionsLabel, extrasLabel, ink) {
        buildAnnotatedString {
            if (level != MenuLevel.FIRST) {
                withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append("◂ ") }
                withStyle(SpanStyle(color = ink)) { append(if (level == MenuLevel.EXTRAS) extrasLabel else collectionsLabel) }
            } else {
                withStyle(SpanStyle(color = ink)) { append(actionsLabel) }
                if (seasonLabel != null) {
                    withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(" │ ") }
                    withStyle(SpanStyle(color = JellybeamTheme.Grigio)) { append(seasonLabel) }
                }
            }
        }
    }
    PanelTitle(kicker, itemName)
}

/** §1.5's header block: the mono [kicker], then [title] verbatim in two lines at most. */
@Composable
internal fun PanelTitle(kicker: AnnotatedString, title: String) {
    Column(modifier = Modifier.padding(horizontal = PANEL_SIDE_PADDING)) {
        BasicText(text = kicker, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, fontSize = 9.sp, letterSpacing = (-0.02).em))
        Box(Modifier.height(8.dp))
        BasicText(
            text = title,
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
internal fun HeadingBand(label: String, isFirst: Boolean) {
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
 * come from bulk-confirm (docs/19 §1.3); [drillIn] draws `▸` instead of [subtext]. docs/29 §8.7 adds
 * the [gutter] that holds ✓ when [checked], and [busy] loading dots in place of the subtext (I2).
 */
@Composable
internal fun PanelActionRow(
    label: String,
    subtext: String?,
    drillIn: Boolean = false,
    dimmed: Boolean = false,
    focusable: Boolean = true,
    focusRequester: FocusRequester? = null,
    onSelect: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    gutter: Boolean = false,
    checked: Boolean = false,
    busy: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accent = LocalAccent.current

    var rowModifier: Modifier = Modifier
    if (focusable && focusRequester != null) rowModifier = rowModifier.focusRequester(focusRequester)
    if (focusable) rowModifier = rowModifier.onFocusChanged { onFocusChanged(it.isFocused) }
    rowModifier = rowModifier
        .fillMaxWidth()
        .padding(horizontal = if (isFocused) PANEL_FOCUSED_INSET else PANEL_SIDE_PADDING)
        .alpha(if (dimmed) 0.4f else 1f)
    if (isFocused) rowModifier = rowModifier.clip(RoundedCornerShape(50)).background(accent.accent)
    if (focusable) rowModifier = rowModifier.clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
    rowModifier = rowModifier.padding(horizontal = if (isFocused) PANEL_FOCUSED_HPADDING else 0.dp, vertical = 7.dp)

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (gutter) {
            Box(Modifier.width(PANEL_GUTTER)) {
                if (checked) BasicText("✓", style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = if (isFocused) accent.onAccent else accent.accentInk, fontSize = 12.sp))
            }
        }
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isFocused) accent.onAccent else JellybeamTheme.Panna2,
                fontSize = 13.sp,
            ),
        )
        if (busy) {
            LoadingDots(color = if (isFocused) accent.onAccent else JellybeamTheme.Panna)
        } else if (drillIn) {
            BasicText(text = "▸", style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.HairlineStrong, fontSize = 8.sp))
        } else if (subtext != null) {
            BasicText(
                text = subtext,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = JellybeamTheme.MartianMono,
                    color = if (isFocused) accent.onAccentMuted else PanelMuted,
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
    val backToCancel = stringResource(R.string.detail_menu_footer_back_to_cancel).uppercaseUi()
    val backForActions = stringResource(R.string.detail_menu_footer_back_for_actions).uppercaseUi()
    val backToClose = stringResource(R.string.detail_menu_footer_close).uppercaseUi()
    val actionsCount = pluralStringResource(R.plurals.detail_menu_footer_actions_count, actionRowCount, actionRowCount).uppercaseUi()
    return remember(level, confirmVisible, actionRowCount, backToCancel, backForActions, backToClose, actionsCount) {
        when {
            confirmVisible -> panelFooterText(backToCancel, null)
            level != MenuLevel.FIRST -> panelFooterText(backForActions, null)
            else -> panelFooterText(backToClose, actionsCount)
        }
    }
}

/** §1.5's footer line: [left], then `│` in its own dim colour and [right] when there is one. */
internal fun panelFooterText(left: String, right: String?): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(left) }
    if (right != null) {
        withStyle(SpanStyle(color = JellybeamTheme.Surface)) { append("  │  ") }
        withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append(right) }
    }
}

@Composable
internal fun PanelFooter(text: AnnotatedString) {
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
    val accent = LocalAccent.current
    val title = if (confirm.played) {
        pluralStringResource(R.plurals.detail_menu_confirm_watched_title, confirm.count, confirm.count)
    } else {
        pluralStringResource(R.plurals.detail_menu_confirm_unwatched_title, confirm.count, confirm.count)
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
            .border(1.dp, accent.accentInk, RoundedCornerShape(7.dp))
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
    val accent = LocalAccent.current
    val ringColor = if (primary) accent.accentSheen else accent.accentInk

    Box(modifier = Modifier.focusRing(isFocused = isFocused, cornerRadius = 999.dp, color = ringColor)) {
        Box(
            modifier = Modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .clip(RoundedCornerShape(50))
                .background(if (primary) accent.accent else Color.Transparent)
                .let { if (!primary) it.border(1.dp, JellybeamTheme.HairlineStrong, RoundedCornerShape(50)) else it }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            BasicText(
                text = label,
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    fontWeight = FontWeight.SemiBold,
                    color = if (primary) accent.onAccent else JellybeamTheme.Panna,
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
