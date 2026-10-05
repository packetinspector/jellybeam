package tv.jellybeam.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.POSTER_CELL_WIDTH
import tv.jellybeam.ui.cards.PosterCard
import tv.jellybeam.ui.cards.PreloadOnDwell
import tv.jellybeam.ui.cards.WatchIndicator
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.FocusTarget
import tv.jellybeam.ui.focus.RefreshRestoreOwner
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.refreshGuardEligible
import tv.jellybeam.ui.focus.releaseCancelledFreezeInPlace
import tv.jellybeam.ui.focus.restoreNow
import tv.jellybeam.ui.nav.LocalDrawerFocusCoordinator
import tv.jellybeam.ui.theme.BackdropScrim
import tv.jellybeam.ui.theme.DETAIL_HORIZONTAL_SCRIM_STOPS
import tv.jellybeam.ui.theme.DETAIL_VERTICAL_SCRIM_STOPS
import uniffi.jellybeam_core.Card

// docs/11 §Collection: the BoxSet page. Focus wiring is [MovieDetailScreen]'s (docs/15): one
// [FocusRestorer], the same refresh guard, and the `···` door/panel hoisted in [DetailScreen].

/** The Play pill is taller than the shared 34dp pill: label plus a subtext line. */
private val COLLECTION_PILL_HEIGHT = 46.dp
private val MEMBER_ROW_GAP = 16.dp
private const val MEMBER_KEY_PREFIX = "member:"
private const val PLAY_KEY = "action:primary"

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CollectionDetailScreen(
    card: Card,
    state: DetailUiState,
    viewModel: DetailViewModel,
    onOpenDetail: (Card) -> Unit,
    isTop: Boolean,
    focusGate: MutableState<Boolean>,
    memory: FocusMemory,
    contentEndInset: Dp,
    contentWidth: Dp,
    panelOpen: Boolean,
) {
    // docs/19 §1.5 FIX D: whole-cards-only windowing is only active while the panel is open.
    val cardWindowActive = contentEndInset > 0.dp
    val members = state.members
    val play = state.collectionPlay
    val strings = rememberUiStrings()

    // The pill resolves with the members (one state write), so [ready] gates the seed on it.
    val seedTarget = remember(card.id, play != null) {
        DetailFormatting.resolveFocusSeedTarget(hasPrimary = play != null, hasSecondary = true)
    }
    val initialFocusRequester = remember(card.id) { FocusRequester() }
    val doorFocusRequester = remember(card.id) { FocusRequester() }
    val collectionFallback: () -> FocusTarget? = {
        (if (seedTarget == DetailFormatting.FocusSeedTarget.PRIMARY) initialFocusRequester else doorFocusRequester).asFocusTarget()
    }
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = state.membersSettled,
        fallback = collectionFallback,
        tag = "detail-collection",
    )

    // docs/15 §5: the member row is this screen's only lazy list.
    val listState = remember(card.id) { LazyListState() }
    SideEffect {
        memory.scrollTo = { key ->
            val index = if (key.startsWith(MEMBER_KEY_PREFIX)) {
                members.indexOfFirst { it.id == key.removePrefix(MEMBER_KEY_PREFIX) }
            } else {
                -1
            }
            if (index >= 0) {
                listState.scrollToItem(index)
                true
            } else {
                false
            }
        }
    }

    // Same REFRESH-STEALS-FOCUS guard as [MovieDetailScreen] (docs/15 §3): a member reload can drop
    // the focused card's key, so one frame later an unfocused page restores to [lastKey]/fallback.
    val guardScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var collectionHasFocus by remember(card.id) { mutableStateOf(false) }
    val focusMarked = remember(card.id) { booleanArrayOf(false) }
    val refreshOwner = remember { RefreshRestoreOwner() }
    val latestIsTop by rememberUpdatedState(isTop)
    LaunchedEffect(isTop) {
        if (!isTop) refreshOwner.ownershipLost()
    }
    LaunchedEffect(panelOpen) {
        if (panelOpen) {
            refreshOwner.ownershipLost()
        } else {
            refreshOwner.releaseCancelledFreezeInPlace(memory, ownsFocus = latestIsTop)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) refreshOwner.ownershipLost()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var drawerOpen by remember(card.id) { mutableStateOf(false) }
    val drawerFocusCoordinator = LocalDrawerFocusCoordinator.current
    if (drawerFocusCoordinator != null) {
        SideEffect {
            drawerFocusCoordinator.onDrawerOpened = {
                drawerOpen = true
                refreshOwner.ownershipLost()
            }
            drawerFocusCoordinator.onDrawerClosed = { navigatingAway ->
                drawerOpen = false
                refreshOwner.releaseCancelledFreezeInPlace(memory, ownsFocus = !navigatingAway && latestIsTop)
                false
            }
        }
    }
    val latestPanelOpen by rememberUpdatedState(panelOpen)
    LaunchedEffect(card, members, play) {
        refreshOwner.onContentChanged(
            scope = guardScope,
            frame = { withFrameNanos {} },
            eligible = { ownsFreeze ->
                !latestPanelOpen && !drawerOpen &&
                    refreshGuardEligible(
                        isTop = latestIsTop,
                        seeded = memory.seeded,
                        resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                        hasFocus = collectionHasFocus,
                        frozen = memory.frozen,
                        ownsFreeze = ownsFreeze,
                    )
            },
            restore = {
                memory.restoreNow(focusGate, fallback = collectionFallback, tag = "detail-collection-refresh")
            },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            .onFocusChanged {
                collectionHasFocus = it.hasFocus
                markDetailFocusOnce(focusMarked, it)
            },
    ) {
        val viewportHeight = maxHeight
        // Full-bleed backdrop and scrim, never inset by the panel's reflow.
        DetailBackdropImage(card = card, artSource = CollectionFormatting.backdropSource(card, members), height = viewportHeight)
        BackdropScrim(
            modifier = Modifier.fillMaxSize(),
            textColumnSide = Alignment.Start,
            horizontalStops = DETAIL_HORIZONTAL_SCRIM_STOPS,
            verticalStops = DETAIL_VERTICAL_SCRIM_STOPS,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // docs/19 §1.5: content-only reflow while the panel is open.
                .padding(end = contentEndInset),
        ) {
            Spacer(modifier = Modifier.weight(0.8f))
            Column(
                modifier = Modifier.padding(horizontal = PAGE_MARGIN).widthIn(max = 700.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                EyebrowText(stringResource(R.string.detail_collection_eyebrow))
                BasicText(
                    text = card.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = DETAIL_TITLE_STYLE,
                )
                if (state.membersSettled) {
                    BasicText(
                        text = CollectionFormatting.metaLine(strings, members),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 13.sp),
                    )
                }
                Row(
                    modifier = Modifier
                        .focusProperties {
                            // Up from the member row lands on Play, not the spatially nearer door; a
                            // programmatic request (panel/drawer close to the door) arrives as Enter.
                            onEnter = {
                                if (play != null && requestedFocusDirection == FocusDirection.Up) initialFocusRequester.requestFocus()
                            }
                        }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (play != null) {
                        CollectionPlayPill(
                            play = play,
                            onClick = viewModel::playCollection,
                            memory = memory,
                            focusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.PRIMARY) initialFocusRequester else null,
                        )
                    }
                    MenuDoorPill(
                        onOpen = { memory.captureInvoker(); viewModel.openMenu() },
                        memory = memory,
                        focusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.SECONDARY) doorFocusRequester else null,
                        size = COLLECTION_PILL_HEIGHT,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (members.isNotEmpty()) {
                MemberRow(
                    members = members,
                    seasonCounts = state.memberSeasonCounts,
                    upNextId = play?.takeIf { !it.replayAll }?.member?.id,
                    onOpenDetail = onOpenDetail,
                    memory = memory,
                    listState = listState,
                    contentWidth = contentWidth,
                    active = cardWindowActive,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** The one horizontal row of member posters in server order. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberRow(
    members: List<Card>,
    seasonCounts: Map<String, Int>,
    upNextId: String?,
    onOpenDetail: (Card) -> Unit,
    memory: FocusMemory,
    listState: LazyListState,
    contentWidth: Dp,
    active: Boolean,
) {
    val density = LocalDensity.current
    val posterWidthPx = remember(density) { CardFormatting.bucketedImageWidth(with(density) { POSTER_CELL_WIDTH.roundToPx() }) }
    // docs/19 §1.5 FIX D: whole poster cards only while the panel is open.
    val window = rememberCardWindow(members, listState, contentWidth, POSTER_CELL_WIDTH, MEMBER_ROW_GAP, active)
    val upNextLabel = stringResource(R.string.detail_up_next)
    val strings = rememberUiStrings()
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(MEMBER_ROW_GAP),
            // Vertical room so the focus ring and scale aren't clipped by the row.
            contentPadding = PaddingValues(horizontal = PAGE_MARGIN, vertical = 8.dp),
        ) {
            itemsIndexed(window.items, key = { _, member -> member.id }) { _, member ->
                var isFocused by remember(member.id) { mutableStateOf(false) }
                PosterCard(
                    card = member,
                    isFocused = isFocused,
                    imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, posterWidthPx) },
                    onClick = { onOpenDetail(member) },
                    modifier = Modifier
                        .focusKey(memory, "$MEMBER_KEY_PREFIX${member.id}")
                        .onFocusChanged { isFocused = it.isFocused },
                    metaOverride = CollectionFormatting.cardSubline(strings, member, seasonCounts[member.id]),
                    indicatorOverride = if (CollectionFormatting.isWatched(member)) WatchIndicator.WatchedCheck else null,
                    topStartTag = if (member.id == upNextId) upNextLabel else null,
                )
            }
        }
    }
}

/** The collection's primary pill: "Play" / "Play again" over a "<target> · <runtime|S E>" line. */
@Composable
private fun CollectionPlayPill(
    play: CollectionFormatting.CollectionPlay,
    onClick: () -> Unit,
    memory: FocusMemory,
    focusRequester: FocusRequester?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val strings = rememberUiStrings()
    val subtext = CollectionFormatting.playSubtext(strings, play)
    // Keyed on the target too: a reload can retarget a focused pill, and the old item's preload must stop.
    // Cleans up only what it started, so a background page never cancels another page's preload.
    DisposableEffect(play.targetId, isFocused) {
        if (!isFocused) return@DisposableEffect onDispose {}
        PreloadOnDwell.default.onPlayableActionFocused(play.targetId)
        onDispose { PreloadOnDwell.default.onCardUnfocused(play.targetId) }
    }

    Box(modifier = Modifier.focusRing(isFocused = isFocused, cornerRadius = COLLECTION_PILL_HEIGHT / 2, color = JellybeamTheme.Sheen)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .focusKey(memory, PLAY_KEY)
                .heightIn(min = COLLECTION_PILL_HEIGHT)
                .widthIn(min = ACTION_BUTTON_MIN_WIDTH)
                .clip(RoundedCornerShape(50))
                .background(JellybeamTheme.Pistacchio)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = ACTION_BUTTON_HPADDING),
        ) {
            BasicText(
                text = CollectionFormatting.playLabel(strings, play),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Notte, fontSize = 15.sp),
            )
            subtext?.let {
                BasicText(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Notte.copy(alpha = 0.75f), fontSize = 11.sp),
                )
            }
        }
    }
}
