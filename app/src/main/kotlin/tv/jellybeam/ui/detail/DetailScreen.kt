package tv.jellybeam.ui.detail

import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.memory.MemoryCache
import java.util.Locale
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.perf.PerfLog
import tv.jellybeam.player.PlaybackActivity
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.BACKDROP_PLACEHOLDER_DIM
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.POSTER_CELL_WIDTH
import tv.jellybeam.ui.cards.PosterCard
import tv.jellybeam.ui.cards.PreloadOnDwell
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.cards.rememberHeaderPageBringIntoViewSpec
import tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.FocusTarget
import tv.jellybeam.ui.focus.RefreshRestoreOwner
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.refreshGuardEligible
import tv.jellybeam.ui.focus.releaseCancelledFreezeInPlace
import tv.jellybeam.ui.focus.restoreNow
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.nav.LocalDrawerFocusCoordinator
import tv.jellybeam.ui.nav.LocalDrawerLeftEdgeSuppressed
import tv.jellybeam.ui.focus.requestFocusWithRetry
import tv.jellybeam.ui.theme.BackdropScrim
import tv.jellybeam.ui.theme.DETAIL_HORIZONTAL_SCRIM_STOPS
import tv.jellybeam.ui.theme.DETAIL_VERTICAL_SCRIM_STOPS
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.ItemDetail
import uniffi.jellybeam_core.PersonInfo

// Episode/Series/Movie detail screens: each item type gets its own purpose-built layout (the
// three per-type composables below) rather than one generic header+action-row+grid shape. What's
// shared -- pill buttons, the spec strip, cast rows, the Similar Titles rail -- lives in the
// composables below the three screens. Series' episode section is a horizontal per-season shelf
// (wrapped in [tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec]) rather than a grid; a plain
// `Modifier.verticalScroll` Column is the page's scroll container for Series/Movie.

private const val SERIES_ITEM_TYPE = "Series"
private const val EPISODE_ITEM_TYPE = "Episode"
private const val SPECIALS_SEASON_NAME = "Specials"

/** §0's shared safe area, matching Home's own updated `PAGE_MARGIN`. */
internal val PAGE_MARGIN = 40.dp

private val EPISODE_BACKDROP_HEIGHT = 350.dp
private val SERIES_BACKDROP_HEIGHT = 330.dp
private val MOVIE_BACKDROP_HEIGHT = 330.dp

// docs/23-detail-layout-rules.md Rule 1: hero poster figure (148x222, radius 4, 26dp gap).
private val HERO_POSTER_WIDTH = 148.dp
private val HERO_POSTER_HEIGHT = 222.dp
private val HERO_POSTER_RADIUS = 4.dp
private val HERO_POSTER_GAP = 26.dp

// docs/23-detail-layout-rules.md Rule 2: one title clamp shared by all three screens.
internal val DETAIL_TITLE_STYLE = TextStyle(
    fontFamily = JellybeamTheme.Archivo,
    fontWeight = FontWeight.ExtraBold,
    color = JellybeamTheme.Panna,
    fontSize = 32.sp,
    lineHeight = 34.sp,
)

/** The overview's own focus-stop key ([OverviewBlock]/[MoreStop]/[SynopsisPanel]'s shared
 * contract).
 */
private const val OVERVIEW_MORE_KEY = "overview:more"

// One pill spec for every Detail screen's action row, enforced via `widthIn(min = ...)`, never
// `fillMaxWidth()`/`weight()` (which would stretch every button uniformly).
// [ACTION_BUTTON_MIN_WIDTH]
// is a floor close to a real single-word label's own width, so it rarely engages on long labels.
// internal (not private): [DetailActionPanel.kt]'s MenuDoorPill reuses these so the door matches
// the primary pill's real, already-tuned geometry exactly (docs/19 §1 rule 1).
internal val ACTION_BUTTON_HEIGHT = 34.dp
internal val ACTION_BUTTON_MIN_WIDTH = 80.dp
internal val ACTION_BUTTON_HPADDING = 24.dp

/** docs/19 §1.5: the panel's own entry/exit tween, and the page's matching reflow -- both share
 * this one duration.
 */
private const val PANEL_SLIDE_MS = 250

private val SECTION_TOP_GAP = 28.dp

// §D.2: one scrim recipe ([tv.jellybeam.ui.theme.DETAIL_HORIZONTAL_SCRIM_STOPS]/
// [tv.jellybeam.ui.theme.DETAIL_VERTICAL_SCRIM_STOPS], a Detail-only pair distinct from Home hero's
// own) for all three Detail screens, except Episode, which needs its own deeper vertical curve --
// see [EPISODE_VERTICAL_SCRIM_STOPS].

/**
 * §C.2: the Episode synopsis can sit as high as ~33% up the 350dp [EPISODE_BACKDROP_HEIGHT]
 * backdrop in the shortest-stack case, past where [tv.jellybeam.ui.theme.DEFAULT_VERTICAL_SCRIM_STOPS]
 * has already faded. This curve holds full opacity out to 40% up before fading to clear by 62%,
 * still leaving the upper ~38% (the eyebrow/title band) visibly lit.
 */
val EPISODE_VERTICAL_SCRIM_STOPS: List<Pair<Float, Float>> = listOf(
    0.00f to 1.00f,
    0.40f to 1.00f,
    0.62f to 0.00f,
)

/**
 * Dispatches to the purpose-built Episode/Series/Movie layout for [card]'s item type. Any other
 * type fails open onto the Movie layout, rendering what it can and omitting the rest.
 * [isTop]/[focusGate] are [tv.jellybeam.MainActivity]'s retained-layer wiring, defaulting to
 * "already top, gate open" for callers (tests, previews) that construct this directly.
 */
@Composable
fun DetailScreen(
    card: Card,
    onOpenDetail: (Card) -> Unit,
    isTop: Boolean = true,
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: DetailViewModel = viewModel(
        key = "detail-${card.id}",
        factory = DetailViewModelFactory(AppGraph.gateway, card),
    ),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    // docs/19 §3.4: one [FocusMemory] shared by whichever per-type screen is active, hoisted here
    // so the panel (rendered once, above the `when`) and the door capture/restore focus by the
    // same key regardless of item type.
    val memory = rememberFocusMemory(card.id)

    // docs/10-perf-logging.md `detail.firstDraw`: one-shot per push, the first pre-draw of this
    // screen's content -- keyed on card.id so a fresh push (new composition) re-arms and a
    // still-loading recomposition of the same card never re-fires it.
    val detailLocalView = LocalView.current
    DisposableEffect(card.id, detailLocalView) {
        if (!PerfLog.enabled) return@DisposableEffect onDispose {}
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                detailLocalView.viewTreeObserver.removeOnPreDrawListener(this)
                PerfLog.markDetailPhase("detail.firstDraw")
                return true
            }
        }
        detailLocalView.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { detailLocalView.viewTreeObserver.removeOnPreDrawListener(listener) }
    }

    // Retained layer (docs/19 §1.5): tells the change-refresh scheduler whether this page is on
    // screen, same wiring as Home and Library.
    LaunchedEffect(isTop) {
        viewModel.setActive(isTop)
    }

    // §1.4: Play*/Go to series rows close the panel and hand back a one-shot target for this
    // screen to launch, since the ViewModel holds no Context.
    // docs/19 §1.5/§3.4: the panel slides rather than vanishing, so [composedMenu] lags one step
    // behind [state.menu] going `null`, staying composed until [panelOffsetX]'s exit animation
    // finishes and its `finishedListener` drops it.
    var composedMenu by remember(card.id) { mutableStateOf<MenuUiState?>(null) }
    LaunchedEffect(state.menu) {
        if (state.menu != null) composedMenu = state.menu
    }
    // docs/15 §4: the panel's close always returns focus to the door by key. Keyed on
    // [composedMenu] (not [state.menu]) so this fires once the panel has actually left
    // composition, not the instant the ViewModel clears state while the exit animation slides.
    var menuWasOpen by remember(card.id) { mutableStateOf(false) }
    // A row that leaves this page (Go to series, any Playback row) drops the panel at once instead
    // of sliding it out, so the still-composed panel's focus trap doesn't outlive the push. No
    // door restore either: this page is about to stop being top.
    fun dropPanelForNavigation() {
        menuWasOpen = false
        composedMenu = null
    }
    LaunchedEffect(state.pendingPlayback) {
        state.pendingPlayback?.let { pending ->
            dropPanelForNavigation()
            PerfLog.markStartup("playback.click")
            PlaybackActivity.launch(context, pending.targetId, startFromBeginning = pending.startFromBeginning)
            viewModel.consumePendingPlayback()
        }
    }
    LaunchedEffect(state.pendingSeriesNavigation) {
        state.pendingSeriesNavigation?.let { series ->
            dropPanelForNavigation()
            onOpenDetail(series)
            viewModel.consumePendingSeriesNavigation()
        }
    }

    val menuOpen = state.menu != null
    val panelOffsetX by animateDpAsState(
        targetValue = if (menuOpen) 0.dp else PANEL_WIDTH,
        animationSpec = tween(PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
        label = "panel-offset",
        finishedListener = { end -> if (!menuOpen && end == PANEL_WIDTH) composedMenu = null },
    )
    // The page's own reflow (§1.5): the content column's end inset grows in lockstep with the
    // panel's entry, shrinks back as it leaves.
    val contentEndInset by animateDpAsState(
        targetValue = if (menuOpen) PANEL_WIDTH else 0.dp,
        animationSpec = tween(PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
        label = "content-end-inset",
    )
    // docs/19 §1.5 FIX D: real content width (screen width minus the panel's current reflow),
    // threaded down alongside [contentEndInset] so below-fold LazyRows slice to whole cards -- see
    // [rememberCardWindow].
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val screenWidthDp = with(LocalDensity.current) { windowWidthPx.toDp() }
    val contentWidth = screenWidthDp - contentEndInset

    // docs/19 §1.5: Left closes the panel; the nav drawer's "focus can't move left" open gesture
    // is always true inside the panel's focus trap, so it stands down while the panel is composed.
    val drawerLeftEdgeSuppressed = LocalDrawerLeftEdgeSuppressed.current
    DisposableEffect(drawerLeftEdgeSuppressed, composedMenu != null) {
        drawerLeftEdgeSuppressed?.value = composedMenu != null
        onDispose { drawerLeftEdgeSuppressed?.value = false }
    }
    LaunchedEffect(composedMenu) {
        if (composedMenu != null) {
            menuWasOpen = true
        } else if (menuWasOpen) {
            menuWasOpen = false
            if (isTop) memory.placeByKeys(listOf(MENU_DOOR_KEY), focusGate)
            // docs/15 §4: placing the door by key (not through `restoreNow`, which clears it
            // itself) left `invokerKey` set, so a later return landed on the door instead of the
            // card last browsed.
            memory.clearInvoker()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // [state.card] is the live mirror card, passed to every per-type screen so header UI
        // tracks playback. The nav `card` is used only for [viewModel]'s key/factory and the
        // `when` dispatch below, since `itemType` never changes.
        // The action panel is a sibling of the per-type body, not a descendant, so a body's root
        // `onFocusChanged` reports `hasFocus = false` while the panel holds focus even though
        // focus is legitimately still on this page. [panelOpen] spans the panel's whole composed
        // lifetime (not just [menuOpen]'s target state) so each body's refresh guard can gate on
        // it.
        val panelOpen = state.menu != null || composedMenu != null
        when (card.itemType) {
            EPISODE_ITEM_TYPE -> EpisodeDetailScreen(state.card, state, viewModel, onOpenDetail, isTop, focusGate, memory, contentEndInset, contentWidth, panelOpen)
            SERIES_ITEM_TYPE -> SeriesDetailScreen(state.card, state, viewModel, onOpenDetail, isTop, focusGate, memory, contentEndInset, contentWidth, panelOpen)
            CollectionFormatting.BOXSET_ITEM_TYPE -> CollectionDetailScreen(state.card, state, viewModel, onOpenDetail, isTop, focusGate, memory, contentEndInset, contentWidth, panelOpen)
            else -> MovieDetailScreen(state.card, state, viewModel, onOpenDetail, isTop, focusGate, memory, contentEndInset, contentWidth, panelOpen)
        }

        // The live model while open, the last-known one while the panel is still sliding out.
        (state.menu ?: composedMenu)?.let { menu ->
            DetailActionPanel(
                menu = menu,
                itemName = state.card.name,
                collections = state.collections,
                memberOfCollections = state.memberOfCollections,
                confirm = state.confirm,
                offsetX = panelOffsetX,
                focusGate = focusGate,
                isTop = isTop,
                onRunAction = viewModel::runAction,
                onEnterCollections = viewModel::enterCollections,
                onSelectCollection = viewModel::selectCollection,
                onBackFromCollections = viewModel::backFromCollections,
                onConfirmBulkMark = viewModel::confirmBulkMark,
                onDismissConfirm = viewModel::dismissConfirm,
                onClose = viewModel::closeMenu,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        state.toast?.let { toast ->
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = PAGE_MARGIN, bottom = 24.dp)) {
                DetailMenuToast(text = toast)
            }
        }
    }
}

// All three Detail screens' focus seeds share the bounded-retry shape
// [tv.jellybeam.ui.library.LibraryScreen]'s
// own seeds use: `focusGate` opens fresh before every attempt, closes the instant one lands, and
// stays open on total failure -- an open gate at least lets the framework's default-focus search
// find something, rather than a closed gate leaving nothing focused and D-pad input going nowhere.

// EPISODE (spec §2)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeDetailScreen(
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
    val context = LocalContext.current
    val detail = state.itemDetail
    // Computed inline, not remembered: a refreshed Card with the same id must recompute rather
    // than show the first composition's stale value (docs/07-home-browse-behavior.md §1).
    val primaryAction = DetailFormatting.resolvePrimaryAction(card, emptyList())
    val hasResume = primaryAction is DetailFormatting.PrimaryAction.Playable && primaryAction.hasProgress
    val eyebrow = DetailFormatting.episodeEyebrow(card.seriesName, card.parentIndexNumber, card.indexNumber)
    val metaItems = DetailFormatting.episodeHeaderItems(card.productionYear, card.runtimeTicks, detail?.officialRating, detail?.genres.orEmpty())
    // docs/17-mini-player.md §6: [card] is the live mirror card, so a resume/watched flip landing
    // after a PiP dismissal is reflected here; not `remember`ed at all.
    val remainingLabel = CardFormatting.remainingLabel(card.runtimeTicks, card.positionTicks)
    val resumeFraction = CardFormatting.watchProgress(card)
    val castMembers = remember(detail) { DetailFormatting.castMembers(detail?.people.orEmpty()) }
    // docs/15 §5: the cast row's LazyRow is the only lazy list here, so [memory]'s scrollTo only
    // ever has one list to search; an unmatched key is a miss per [FocusMemory.scrollTo]'s
    // contract.
    val castListState = remember(card.id) { LazyListState() }
    SideEffect {
        memory.scrollTo = { key ->
            val personId = key.removePrefix("person:")
            val index = if (personId != key) castMembers.indexOfFirst { it.id == personId } else -1
            if (index >= 0) {
                castListState.scrollToItem(index)
                true
            } else {
                false
            }
        }
    }
    // docs/23-detail-layout-rules.md Rule 2's full-synopsis panel.
    var synopsisOpen by remember(card.id) { mutableStateOf(false) }
    val synopsisScope = rememberCoroutineScope()
    // Round-3 punch list, items 2+3: the lower band's own visibility check
    // (below) needs to know up front whether the spec-strip cell will
    // actually render anything, same "no SpecStripRow means no stray gap"
    // contract [SpecStripRow] itself already enforces by returning early.
    val specFields = remember(detail) { detail?.let(DetailFormatting::specStripFields).orEmpty() }

    // Both of this screen's possible seed targets resolve straight from [card], no async fetch
    // gate, so [seedTarget] is stable from the first composition. The door renders unconditionally
    // (docs/19), so `hasSecondary` is always true and [DetailFormatting.FocusSeedTarget.NONE] can't
    // happen here; [resolveFocusSeedTarget] stays in place so wiring matches Series/Movie's shape.
    val hasPrimaryAction = primaryAction != DetailFormatting.PrimaryAction.None
    val seedTarget = remember(card.id, hasPrimaryAction) {
        DetailFormatting.resolveFocusSeedTarget(hasPrimaryAction, hasSecondary = true)
    }

    val initialFocusRequester = remember(card.id) { FocusRequester() }
    val doorFocusRequester = remember(card.id) { FocusRequester() }
    // Hoisted so [FocusRestorer] and the refresh guard below share one fallback.
    val episodeFallback: () -> FocusTarget? = {
        (if (seedTarget == DetailFormatting.FocusSeedTarget.PRIMARY) initialFocusRequester else doorFocusRequester).asFocusTarget()
    }
    // docs/15 §5: one shared restore mechanism; the door always renders, so this screen always has
    // something focusable.
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        fallback = episodeFallback,
        tag = "detail-episode",
    )

    // REFRESH-STEALS-FOCUS guard (docs/15 §3): a live mirror refresh can replace
    // [card]/[state.itemDetail]/[state.nextEpisode] with fresh values that no longer contain the
    // focused element's key (a cast member drops off, or the Up Next target disappears) -- both
    // are keyed lazy lists, so Compose disposes the focused node with nothing re-placing it. One
    // `withFrameNanos` lets Compose apply the new values (and clear focus, if it's going to)
    // before reading [episodeHasFocus]; only a refresh that actually left focus nowhere restores.
    // [panelOpen] is checked explicitly since the action panel is a sibling, not a descendant, so
    // it never shows up in [episodeHasFocus] and never touches [FocusMemory.frozen] itself.
    val lifecycleOwner = LocalLifecycleOwner.current
    var episodeHasFocus by remember(card.id) { mutableStateOf(false) }
    // docs/10-perf-logging.md `detail.focus`: one-shot per push, the moment initial focus lands.
    val episodeFocusMarked = remember(card.id) { booleanArrayOf(false) }
    // [RefreshRestoreOwner] owns the refresh guard's `restoreNow` coroutine and its freeze;
    // [refreshGuardEligible] is the shared predicate. Cancelled wherever this body stops owning
    // focus: losing `isTop`, the action panel opening, losing RESUMED, or the drawer opening.
    val refreshOwner = remember { RefreshRestoreOwner() }
    // The live top state every ownership hook below reads (`rememberUpdatedState`, never the
    // captured `isTop` param).
    val latestIsTop by rememberUpdatedState(isTop)
    LaunchedEffect(isTop) {
        if (!isTop) refreshOwner.ownershipLost()
    }
    LaunchedEffect(panelOpen) {
        // The panel's own open/close path never touches [memory.frozen]
        // (see this file's own `panelOpen` doc comment), so the owner's
        // claim is kept across the open (plain `ownershipLost`; the guard
        // is blocked by `latestPanelOpen` meanwhile) and the close is an
        // in-place regain: a freeze a cancelled refresh restore left
        // behind is released here or it never lifts (docs/15-focus-and-
        // selection.md §3, [RefreshRestoreOwner.releaseCancelledFreeze]).
        if (panelOpen) {
            refreshOwner.ownershipLost()
        } else {
            // `panelOpen` also drops to false when the panel is dropped FOR
            // a navigation (`dropPanelForNavigation`: Go to series, the
            // playback rows) -- [latestIsTop] keeps that from clearing the
            // navigation freeze (docs/15-focus-and-selection.md §3).
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
    // P2-b: opening the drawer leaves this body top+resumed with focus moved outside its root and
    // [memory] not frozen, so a live refresh could steal focus back behind it. [drawerOpen] closes
    // that gap without adding new freeze/restore machinery; [onDrawerClosed] returns `false` and
    // lets [tv.jellybeam.ui.nav.NavDrawerHost]'s generic restore keep doing its own job.
    var drawerOpen by remember(card.id) { mutableStateOf(false) }
    val drawerFocusCoordinator = LocalDrawerFocusCoordinator.current
    if (drawerFocusCoordinator != null) {
        // `SideEffect`, not `remember`, so these closures always see the current recomposition's
        // state.
        SideEffect {
            drawerFocusCoordinator.onDrawerOpened = {
                drawerOpen = true
                refreshOwner.ownershipLost()
            }
            drawerFocusCoordinator.onDrawerClosed = { navigatingAway ->
                drawerOpen = false
                // In-place close only: a navigating-away close, or one after this screen already
                // lost top, must keep the navigation freeze instead (see
                // [releaseCancelledFreezeInPlace]).
                refreshOwner.releaseCancelledFreezeInPlace(memory, ownsFocus = !navigatingAway && latestIsTop)
                false
            }
        }
    }
    // [latestPanelOpen] is read (`rememberUpdatedState`) instead of the `panelOpen` param
    // directly, so a panel-open landing mid-`frame` can't be outrun by a stale eligibility read.
    val latestPanelOpen by rememberUpdatedState(panelOpen)
    LaunchedEffect(card, state.itemDetail, state.nextEpisode) {
        refreshOwner.onContentChanged(
            scope = synopsisScope,
            frame = { withFrameNanos {} },
            eligible = { ownsFreeze ->
                !latestPanelOpen && !drawerOpen &&
                    refreshGuardEligible(
                        isTop = latestIsTop,
                        seeded = memory.seeded,
                        resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                        hasFocus = episodeHasFocus,
                        frozen = memory.frozen,
                        ownsFreeze = ownsFreeze,
                    )
            },
            restore = {
                memory.restoreNow(focusGate, fallback = episodeFallback, tag = "detail-episode-refresh")
            },
        )
    }

    // Provided at the scrolling container itself; see [rememberHeaderPageBringIntoViewSpec].
    val episodeScrollState = rememberScrollState()
    var episodeHeaderBottomPx by remember(card.id) { mutableFloatStateOf(Float.NaN) }

    @OptIn(ExperimentalFoundationApi::class)
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides rememberHeaderPageBringIntoViewSpec(episodeScrollState) { episodeHeaderBottomPx },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // REFRESH-STEALS-FOCUS FIX: tracks [episodeHasFocus] for the
            // refresh guard above. Wraps the scrolling Column below AND the
            // full-synopsis panel (a nested surface, not a sibling like the
            // action panel), so a synopsis-open window already reads as
            // "focus is here" with no extra flag needed.
            .onFocusChanged {
                episodeHasFocus = it.hasFocus
                markDetailFocusOnce(episodeFocusMarked, it)
            },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            .verticalScroll(episodeScrollState),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Computed inline, not remembered, so a refreshed Card recomputes instead of staying
            // stale.
            val artSource = CardFormatting.backdropArtSource(card)
            DetailBackdropImage(card = card, artSource = artSource, height = EPISODE_BACKDROP_HEIGHT)
            BackdropScrim(
                modifier = Modifier.fillMaxWidth().height(EPISODE_BACKDROP_HEIGHT),
                textColumnSide = Alignment.Start,
                horizontalStops = DETAIL_HORIZONTAL_SCRIM_STOPS,
                verticalStops = EPISODE_VERTICAL_SCRIM_STOPS,
            )

            // This whole screen is one top-anchored, flowed Column (text block -> lower band ->
            // cast row) so no section's position is ever hardcoded to a screen y that could drift
            // out of sync with content above it and overlap.
            val showLowerBand = specFields.isNotEmpty() || state.nextEpisode != null

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 75.dp)
                    .fillMaxWidth()
                    // docs/19 §1.5: the page's reflow while the panel is open -- content only,
                    // never the backdrop/scrim.
                    .padding(end = contentEndInset),
            ) {
                Column(
                    modifier = Modifier.padding(start = PAGE_MARGIN).width(510.dp),
                    verticalArrangement = Arrangement.spacedBy(EPISODE_TEXT_GAP),
                ) {
                    eyebrow?.let { EyebrowText(it) }
                    BasicText(
                        text = card.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = DETAIL_TITLE_STYLE,
                    )
                    if (metaItems.isNotEmpty()) {
                        // docs/19 §1.5 FIX B: drops whole trailing items as the panel reflow
                        // narrows this 510dp column.
                        ItemBoundaryLine(
                            items = metaItems,
                            separator = DetailFormatting.META_SEPARATOR,
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 14.sp),
                        )
                    }
                    if (hasResume && resumeFraction != null) {
                        ResumeProgressRow(fraction = resumeFraction, remainingLabel = remainingLabel)
                    }
                    // The button row closes the header for [rememberHeaderPageBringIntoViewSpec].
                    // No extra margin: [EPISODE_TEXT_GAP] already clears the ring outset.
                    Box(
                        modifier = Modifier.onGloballyPositioned {
                            episodeHeaderBottomPx = it.boundsInRoot().bottom + episodeScrollState.value
                        },
                    ) {
                        EpisodeActionRow(
                            card = card,
                            action = primaryAction,
                            seedTarget = seedTarget,
                            focusRequester = initialFocusRequester,
                            doorFocusRequester = doorFocusRequester,
                            memory = memory,
                            context = context,
                            onOpenMenu = { memory.captureInvoker(); viewModel.openMenu() },
                        )
                    }
                    OverviewBlock(
                        text = card.overview,
                        color = JellybeamTheme.Panna2,
                        memory = memory,
                        moreKey = OVERVIEW_MORE_KEY,
                        onMore = { synopsisOpen = true },
                    )
                    CreditsLine(detail?.directors, detail?.writers, emptyList())
                }

                if (showLowerBand) {
                    Box(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = PAGE_MARGIN, end = PAGE_MARGIN),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                            if (specFields.isNotEmpty() && detail != null) {
                                SpecStripRow(itemType = card.itemType, detail = detail, extraFields = emptyList())
                            }
                        }
                        state.nextEpisode?.let { next ->
                            UpNextPanel(nextEpisode = next, onClick = { onOpenDetail(next) }, memory = memory)
                        }
                    }
                }

                // docs/23-detail-layout-rules.md Rule 1: sections flow, the page scrolls,
                // nothing can overlap.
                if (castMembers.isNotEmpty()) {
                    Box(modifier = Modifier.height(12.dp))
                    CastRow(
                        members = castMembers,
                        memory = memory,
                        listState = castListState,
                        showRole = false,
                        showHeader = false,
                        contentWidth = contentWidth,
                        active = cardWindowActive,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Box(modifier = Modifier.height(32.dp))
    }
    if (synopsisOpen) {
        SynopsisPanel(
            title = card.name,
            overview = card.overview.orEmpty(),
            open = synopsisOpen,
            onClose = {
                synopsisOpen = false
                synopsisScope.launch { memory.placeByKeys(listOf(OVERVIEW_MORE_KEY), focusGate) }
            },
            isTop = isTop,
            focusGate = focusGate,
        )
    }
    }
    }
}

// The Episode text column's gap (Movie/Series use 11dp; this page has two more rows to fit).
private val EPISODE_TEXT_GAP = 8.dp

/**
 * §2 item 3's Episode button row, docs/19 rules 1-2: Resume/Play, then the `···` door at position
 * two. The door renders unconditionally, even with no primary action, so this row is never empty.
 * [focusRequester] seeds the primary pill when [seedTarget] is PRIMARY; [doorFocusRequester] seeds
 * the door otherwise.
 */
@Composable
private fun EpisodeActionRow(
    card: Card,
    action: DetailFormatting.PrimaryAction,
    seedTarget: DetailFormatting.FocusSeedTarget,
    focusRequester: FocusRequester,
    doorFocusRequester: FocusRequester,
    memory: FocusMemory,
    context: android.content.Context,
    onOpenMenu: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (action != DetailFormatting.PrimaryAction.None) {
            val label = when (action) {
                is DetailFormatting.PrimaryAction.Playable -> action.label
                is DetailFormatting.PrimaryAction.Unavailable -> action.label
                DetailFormatting.PrimaryAction.None -> ""
            }
            PrimaryPillButton(
                label = label,
                enabled = action is DetailFormatting.PrimaryAction.Playable,
                onClick = {
                    if (action is DetailFormatting.PrimaryAction.Playable) {
                        PerfLog.markStartup("playback.click")
                        PlaybackActivity.launch(context, action.targetId)
                    }
                },
                memory = memory,
                key = "action:primary",
                focusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.PRIMARY) focusRequester else null,
                preloadItemId = (action as? DetailFormatting.PrimaryAction.Playable)?.targetId,
            )
        }
        MenuDoorPill(
            onOpen = onOpenMenu,
            memory = memory,
            focusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.SECONDARY) doorFocusRequester else null,
        )
    }
}

// Shrunk from 122x69dp now that the panel flows in the lower band rather than floating over the
// backdrop.
private val UP_NEXT_THUMB_WIDTH = 96.dp
private val UP_NEXT_THUMB_HEIGHT = 54.dp
private val UP_NEXT_PANEL_PADDING = 14.dp
private val UP_NEXT_THUMB_TITLE_GAP = 11.dp

/**
 * §C.1: mono "UP NEXT" label, then one row of (thumb, 11dp gap, title-over-meta column) -- no
 * fixed height anywhere, so the panel hugs exactly what it draws. It's the right cell of
 * [EpisodeDetailScreen]'s lower-band Row, not a screen-relative overlay.
 */
@Composable
private fun UpNextPanel(nextEpisode: Card, onClick: () -> Unit, memory: FocusMemory, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Computed inline, not remembered, so a refreshed nextEpisode recomputes instead of staying
    // stale.
    val artSource = CardFormatting.railArtSource(nextEpisode)
    val metaLine = DetailFormatting.runtimeAndDateLine(nextEpisode.runtimeTicks, nextEpisode.premiereDate)
    val panelWidth = 310.dp
    val titleColumnWidth = panelWidth - UP_NEXT_PANEL_PADDING * 2 - UP_NEXT_THUMB_WIDTH - UP_NEXT_THUMB_TITLE_GAP
    val density = LocalDensity.current
    val thumbImageWidth = remember(density) {
        CardFormatting.bucketedImageWidth(with(density) { UP_NEXT_THUMB_WIDTH.roundToPx() })
    }

    Column(
        modifier = modifier
            .focusKey(memory, "up-next")
            .width(panelWidth)
            .focusRing(isFocused = isFocused, cornerRadius = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(JellybeamTheme.Notte.copy(alpha = 0.92f))
            .border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(4.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(UP_NEXT_PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            text = stringResource(R.string.detail_up_next),
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Pistacchio, fontSize = 10.sp, letterSpacing = (-0.02).em),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(UP_NEXT_THUMB_TITLE_GAP)) {
            Box(
                modifier = Modifier
                    .size(UP_NEXT_THUMB_WIDTH, UP_NEXT_THUMB_HEIGHT)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JellybeamTheme.SurfaceRaised),
            ) {
                CardArtImage(
                    source = artSource,
                    imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, thumbImageWidth) },
                    itemName = nextEpisode.name,
                    contentAlpha = 1f,
                    blurhash = nextEpisode.blurhash,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.width(titleColumnWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    text = nextEpisode.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 13.5.sp, lineHeight = 17.sp),
                )
                metaLine?.let {
                    BasicText(text = it, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 11.sp))
                }
            }
        }
    }
}

// SERIES (spec §3)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesDetailScreen(
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
    val context = LocalContext.current
    val detail = state.itemDetail
    val seasons = state.seasons // already index-number sorted; Specials (index 0) sorts first, matching the chip row.
    val selectedSeason = remember(seasons, state.selectedSeasonId) { seasons.firstOrNull { it.id == state.selectedSeasonId } }
    val primaryAction = remember(card.id, state.allEpisodes) { DetailFormatting.resolvePrimaryAction(card, state.allEpisodes) }
    val hasPrimaryButton = primaryAction != DetailFormatting.PrimaryAction.None
    val hasChips = seasons.size > 1
    val castMembers = remember(detail) { DetailFormatting.castMembers(detail?.people.orEmpty()) }

    val initialFocusRequester = remember(card.id) { FocusRequester() }
    val selectedChipFocusRequester = remember(card.id) { FocusRequester() }
    val doorFocusRequester = remember(card.id) { FocusRequester() }
    // Scrolls the newly-selected chip into view without requesting focus:
    // [BringIntoViewRequester.bringIntoView] only moves the page's [verticalScroll] container,
    // independent of the focus/seed machinery.
    val selectedChipBringIntoViewRequester = remember(card.id) { BringIntoViewRequester() }
    LaunchedEffect(state.selectedSeasonId) {
        if (state.selectedSeasonId != null) selectedChipBringIntoViewRequester.bringIntoView()
    }

    // [seedTarget] is keyed directly on the two independent readiness signals ([hasPrimaryButton],
    // [hasChips], each resolved by its own separately-timed fetch), so [FocusRestorer]'s [ready]
    // fires the instant either one resolves rather than waiting on the unrelated other one.
    // [DetailFormatting.resolveFocusSeedTarget] keeps "primary button wins, chip row only a
    // fallback"; [FocusRestorer]'s own multi-frame retry then guards a single un-retried attempt
    // losing the race against this screen's layout on a freshly pushed composition.
    val seedTarget = remember(card.id, hasPrimaryButton, hasChips) {
        DetailFormatting.resolveFocusSeedTarget(hasPrimaryButton, hasChips)
    }
    // docs/19-detail-action-menu.md's door renders unconditionally (position
    // two, even with no primary action), so the old NONE case -- no primary
    // button AND no season chips -- now falls to the door instead of
    // leaving the page with nothing focusable at all.
    val seedFocusRequester = when (seedTarget) {
        DetailFormatting.FocusSeedTarget.PRIMARY -> initialFocusRequester
        DetailFormatting.FocusSeedTarget.SECONDARY -> selectedChipFocusRequester
        DetailFormatting.FocusSeedTarget.NONE -> doorFocusRequester
    }

    // docs/15-focus-and-selection.md §5: one shared restore mechanism.
    // [selectedKey] is the selected season chip (§2 rule 2) -- Series is
    // the one Detail variant with a "current value" to fall back to before
    // reaching [seedFocusRequester]'s own §2 rule 3 primary.
    // [ready]: a primary pill or a season chip is the fresh-entry target
    // (docs/15 §2 rules 2-3); the door is only the fallback of last resort,
    // once the seasons fetch has settled with neither. Without this gate the
    // restorer fired on the first composition and seeded the door before
    // the seasons had even arrived.
    // Hoisted so [FocusRestorer] (becoming-top) and the refresh guard below
    // (same-composition) share one §2-rule-3 fallback -- same relationship
    // [tv.jellybeam.ui.library.LibraryScreen]'s own `libraryFallback` has to
    // its [FocusRestorer] call.
    val seriesFallback: () -> FocusTarget? = { seedFocusRequester.asFocusTarget() }
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = seedTarget != DetailFormatting.FocusSeedTarget.NONE || state.seasonsSettled,
        selectedKey = { state.selectedSeasonId?.let { "season:$it" } },
        fallback = seriesFallback,
        tag = "detail-series",
    )

    // docs/15 §5: [FocusMemory.scrollTo] for this screen's three lazy lists. [episodeListState] is
    // keyed on [state.selectedSeasonId] so a season switch starts fresh and a return restores the
    // right list.
    val episodeListState = remember(state.selectedSeasonId) { LazyListState() }
    val castListState = remember(card.id) { LazyListState() }
    val similarListState = remember(card.id) { LazyListState() }
    SideEffect {
        memory.scrollTo = { key ->
            when {
                key.startsWith("episode:") -> {
                    val index = state.episodes.indexOfFirst { it.id == key.removePrefix("episode:") }
                    if (index >= 0) { episodeListState.scrollToItem(index); true } else false
                }
                key.startsWith("person:") -> {
                    val index = castMembers.indexOfFirst { it.id == key.removePrefix("person:") }
                    if (index >= 0) { castListState.scrollToItem(index); true } else false
                }
                key.startsWith("similar:") -> {
                    val index = state.similar.indexOfFirst { it.id == key.removePrefix("similar:") }
                    if (index >= 0) { similarListState.scrollToItem(index); true } else false
                }
                else -> false
            }
        }
    }
    // docs/23-detail-layout-rules.md Rule 2's full-synopsis panel.
    var synopsisOpen by remember(card.id) { mutableStateOf(false) }
    val synopsisScope = rememberCoroutineScope()

    // Same REFRESH-STEALS-FOCUS guard as [EpisodeDetailScreen] (docs/15 §3): a live refresh can
    // replace [card]/[seasons]/[state.episodes]/[castMembers]/[state.similar], all keyed rows,
    // dropping the focused key with nothing re-placing it. Unlike Episode/Movie, this screen has a
    // §2 rule 2 "selected item" (the season chip), passed to [FocusRestorer] as `selectedKey` so
    // `restoreNow` tries `[lastKey, selectedKey]` before `fallback`. [seriesRefreshFallback] runs
    // only once both have missed: it tries the episode shelf's first visible cell before
    // [seriesFallback].
    val lifecycleOwner = LocalLifecycleOwner.current
    var seriesHasFocus by remember(card.id) { mutableStateOf(false) }
    // docs/10-perf-logging.md `detail.focus`: one-shot per push, the moment initial focus lands.
    val seriesFocusMarked = remember(card.id) { booleanArrayOf(false) }
    // Same [RefreshRestoreOwner] shape as [EpisodeDetailScreen]; [refreshGuardEligible] is the
    // shared predicate.
    val refreshOwner = remember { RefreshRestoreOwner() }
    val latestIsTop by rememberUpdatedState(isTop)
    LaunchedEffect(isTop) {
        if (!isTop) refreshOwner.ownershipLost()
    }
    LaunchedEffect(panelOpen) {
        // Same shape as [EpisodeDetailScreen]'s identical hook.
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
    // P2-b: same shape as [EpisodeDetailScreen]'s identical fix.
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
    LaunchedEffect(card, seasons, state.episodes, castMembers, state.similar) {
        refreshOwner.onContentChanged(
            scope = synopsisScope,
            frame = { withFrameNanos {} },
            eligible = { ownsFreeze ->
                !latestPanelOpen && !drawerOpen &&
                    refreshGuardEligible(
                        isTop = latestIsTop,
                        seeded = memory.seeded,
                        resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                        hasFocus = seriesHasFocus,
                        frozen = memory.frozen,
                        ownsFreeze = ownsFreeze,
                    )
            },
            restore = {
                memory.restoreNow(
                    focusGate,
                    selectedKey = { state.selectedSeasonId?.let { "season:$it" } },
                    fallback = { seriesRefreshFallback(memory, state.episodes, episodeListState, seriesFallback) },
                    tag = "detail-series-refresh",
                )
            },
        )
    }

    // Same frame rule as Movie ([frameBringIntoViewSpec]): the resume season's chip taking focus
    // on a fresh entry used to make the TV pivot scroll the hero off the top with a visible jolt.
    // The chip lives inside the first viewport, so the frame rule keeps the page at the top.
    val seriesScrollState = rememberScrollState()
    val seriesBringIntoViewSpec = remember(seriesScrollState) { frameBringIntoViewSpec(seriesScrollState) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Tracks [seriesHasFocus] for the refresh guard above; wraps the synopsis panel too, no
            // extra flag needed.
            .onFocusChanged {
                seriesHasFocus = it.hasFocus
                markDetailFocusOnce(seriesFocusMarked, it)
            },
    ) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides seriesBringIntoViewSpec) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            .verticalScroll(seriesScrollState),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Computed inline, not remembered, so a refreshed Card recomputes instead of staying
            // stale.
            val artSource = CardFormatting.backdropArtSource(card)
            DetailBackdropImage(card = card, artSource = artSource, height = SERIES_BACKDROP_HEIGHT)
            BackdropScrim(
                modifier = Modifier.fillMaxWidth().height(SERIES_BACKDROP_HEIGHT),
                textColumnSide = Alignment.Start,
                horizontalStops = DETAIL_HORIZONTAL_SCRIM_STOPS,
                verticalStops = DETAIL_VERTICAL_SCRIM_STOPS,
            )

            // docs/23-detail-layout-rules.md Rule 1: one flow column, no absolute content below
            // the backdrop/scrim pair.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp)
                    // docs/19 §1.5: the page's reflow while the panel is open -- content only,
                    // never the backdrop/scrim.
                    .padding(end = contentEndInset),
            ) {
                Row(
                    // docs/19 §1.5 FIX A: `end = PAGE_MARGIN` wraps the text column 40dp short of
                    // the panel edge while
                    // open; closed-state neutral since 40+150+gap+510 < 960-40.
                    modifier = Modifier.padding(start = PAGE_MARGIN, end = PAGE_MARGIN),
                    horizontalArrangement = Arrangement.spacedBy(HERO_POSTER_GAP),
                    verticalAlignment = Alignment.Top,
                ) {
                    HeroPoster(card)
                    Column(
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 510.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        state.libraryName?.let { EyebrowText(it) }
                        BasicText(
                            text = card.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = DETAIL_TITLE_STYLE,
                        )
                        // Computed inline, not remembered, so a refreshed card/detail field
                        // recomputes instead of staying stale.
                        val headerItems = DetailFormatting.seriesHeaderItems(
                            // detail first: a View-Series synthesized Card has no year
                            productionYear = detail?.productionYear ?: card.productionYear,
                            endYear = detail?.endYear,
                            status = detail?.status,
                            seasonCount = detail?.childCount,
                            episodeCount = detail?.recursiveItemCount,
                            officialRating = detail?.officialRating,
                            genres = detail?.genres.orEmpty(),
                        )
                        if (headerItems.isNotEmpty()) {
                            // docs/19 §1.5 FIX B: drops whole trailing items as the panel reflow
                            // narrows this column.
                            ItemBoundaryLine(
                                items = headerItems,
                                separator = DetailFormatting.META_SEPARATOR,
                                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 14.sp),
                            )
                        }
                        // [DetailFormatting.seriesCardFrom]'s synthesized card always sets
                        // `overview = null`, so [detail] wins over `card.overview` here.
                        SeriesActionRow(
                            card = card,
                            action = primaryAction,
                            focusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.PRIMARY) initialFocusRequester else null,
                            doorFocusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.NONE) doorFocusRequester else null,
                            memory = memory,
                            context = context,
                            onOpenMenu = { memory.captureInvoker(); viewModel.openMenu() },
                        )
                        // Same order as Movie/Episode: actions then overview, so Down from the
                        // primary pill reaches the MORE stop.
                        OverviewBlock(
                            text = detail?.overview ?: card.overview,
                            color = JellybeamTheme.Grigio,
                            memory = memory,
                            moreKey = OVERVIEW_MORE_KEY,
                            onMore = { synopsisOpen = true },
                        )
                        CreditsLine(detail?.directors, detail?.writers, detail?.studios)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                SeasonSelectorSection(
                    seasons = seasons,
                    selectedSeasonId = state.selectedSeasonId,
                    selectedSeason = selectedSeason,
                    episodes = state.episodes,
                    onSelect = viewModel::selectSeason,
                    memory = memory,
                    selectedChipFocusRequester = selectedChipFocusRequester,
                    selectedChipBringIntoViewRequester = selectedChipBringIntoViewRequester,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))
                SeasonEpisodeShelf(
                    episodes = state.episodes,
                    isLoading = DetailFormatting.showEpisodeSkeleton(true, state.isLoadingSeasons, state.isLoadingEpisodes),
                    onOpenDetail = onOpenDetail,
                    memory = memory,
                    listState = episodeListState,
                    contentWidth = contentWidth,
                    active = cardWindowActive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // docs/19 §1.5: these rows are siblings of the flow column above, so they carry the panel
        // inset themselves.
        if (castMembers.isNotEmpty()) {
            Box(modifier = Modifier.padding(top = SECTION_TOP_GAP, end = contentEndInset)) {
                CastRow(
                    members = castMembers,
                    memory = memory,
                    listState = castListState,
                    showHeader = true,
                    showRole = true,
                    contentWidth = contentWidth,
                    active = cardWindowActive,
                )
            }
        }

        if (state.similarLoaded && state.similar.isNotEmpty()) {
            Box(modifier = Modifier.padding(top = SECTION_TOP_GAP, end = contentEndInset)) {
                SimilarRow(
                    items = state.similar,
                    onOpenDetail = onOpenDetail,
                    memory = memory,
                    listState = similarListState,
                    contentWidth = contentWidth,
                    active = cardWindowActive,
                )
            }
        }

        Box(modifier = Modifier.height(32.dp))
    }
    }
    if (synopsisOpen) {
        SynopsisPanel(
            title = card.name,
            overview = (detail?.overview ?: card.overview).orEmpty(),
            open = synopsisOpen,
            onClose = {
                synopsisOpen = false
                synopsisScope.launch { memory.placeByKeys(listOf(OVERVIEW_MORE_KEY), focusGate) }
            },
            isTop = isTop,
            focusGate = focusGate,
        )
    }
    }
}

/**
 * docs/15 §3's rule-2-then-3 tail for the Series refresh guard: by the time this runs,
 * `restoreNow` has already missed on both [FocusMemory.lastKey] and the selected season chip's
 * key. Tries the episode shelf's first visible cell next (same "stale key -> first visible"
 * corollary as Home/Library's fallbacks) before falling through to [seriesFallback].
 */
private fun seriesRefreshFallback(
    memory: FocusMemory,
    episodes: List<Card>,
    episodeListState: LazyListState,
    seriesFallback: () -> FocusTarget?,
): FocusTarget? {
    if (episodes.isNotEmpty()) {
        val index = episodeListState.firstVisibleItemIndex.coerceIn(0, episodes.lastIndex)
        memory.target("episode:${episodes[index].id}")?.let { return it }
    }
    return seriesFallback()
}

/** §3 item 5's Series button row: "Resume S3 E2"/Play, then the `···` door at position two (docs/19
 * rules 1-2). The door renders even with no primary action, so this row is never empty.
 */
@Composable
private fun SeriesActionRow(
    card: Card,
    action: DetailFormatting.PrimaryAction,
    focusRequester: FocusRequester?,
    doorFocusRequester: FocusRequester?,
    memory: FocusMemory,
    context: android.content.Context,
    onOpenMenu: () -> Unit,
) {
    // No vertical margin (unlike Episode's row): the hero column's 11dp gap already clears the ring
    // outset.
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (action != DetailFormatting.PrimaryAction.None) {
            val label = when (action) {
                is DetailFormatting.PrimaryAction.Playable -> action.label
                is DetailFormatting.PrimaryAction.Unavailable -> action.label
                DetailFormatting.PrimaryAction.None -> ""
            }
            PrimaryPillButton(
                label = label,
                enabled = action is DetailFormatting.PrimaryAction.Playable,
                onClick = {
                    if (action is DetailFormatting.PrimaryAction.Playable) {
                        PerfLog.markStartup("playback.click")
                        PlaybackActivity.launch(context, action.targetId)
                    }
                },
                memory = memory,
                key = "action:primary",
                focusRequester = focusRequester,
                preloadItemId = (action as? DetailFormatting.PrimaryAction.Playable)?.targetId,
            )
        }
        MenuDoorPill(
            onOpen = onOpenMenu,
            memory = memory,
            focusRequester = doorFocusRequester,
        )
    }
}

/**
 * §D.5: mono "SEASONS" label, chip row, and right-aligned summary all land on one row. docs/19
 * §1.5 FIX C: the chip row is a plain [Row] inside [Modifier.horizontalScroll] (not a wrapping
 * [FlowRow]), so it scrolls instead of wrapping to a second line. Layout priority left to right:
 * label (fixed width), chips (intrinsic width, scroll once they exceed what's left), then the
 * summary ([ItemBoundaryLine] in a `weight(1f)` Box) which absorbs whatever's left and drops its
 * own items end-first rather than wrapping. Chips always win the room fight.
 * [DetailFormatting.showSeasonsLabel] drops the "SEASONS" label past a season-count threshold
 * (a count heuristic, not a measured fit check) to leave the chip row more room.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SeasonSelectorSection(
    seasons: List<Card>,
    selectedSeasonId: String?,
    selectedSeason: Card?,
    episodes: List<Card>,
    onSelect: (String) -> Unit,
    memory: FocusMemory,
    selectedChipFocusRequester: FocusRequester,
    selectedChipBringIntoViewRequester: BringIntoViewRequester,
    modifier: Modifier = Modifier,
) {
    if (seasons.size <= 1) return
    val summaryItems = selectedSeason?.let { DetailFormatting.seasonSummaryItems(it.name, episodes) }.orEmpty()
    val showLabel = DetailFormatting.showSeasonsLabel(seasons.size)

    Row(
        modifier = modifier.padding(start = PAGE_MARGIN, end = PAGE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLabel) {
            BasicText(
                text = stringResource(R.string.detail_seasons),
                style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 10.sp, letterSpacing = (-0.02).em),
            )
        }
        Row(
            // docs/15 §2 applies to entering a group, not only a screen: D-pad entry into this row
            // (Down from the pills, Up from the shelf) lands on the selected chip, never whichever
            // chip sits spatially nearest.
            modifier = Modifier
                // The scroll container clips on its own axis, and the focus ring draws
                // [SEASON_CHIP_RING_ROOM] outside the chip; room for it inside the clip, offset
                // back out so the first chip still sits on the page margin.
                .offset(x = -SEASON_CHIP_RING_ROOM)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = SEASON_CHIP_RING_ROOM)
                .focusProperties {
                    // Yields during a programmatic restore ([memory.frozen] spans exactly that
                    // window): rule 1 (the chip the viewer left) must beat rule 2 there.
                    enter = { if (selectedSeasonId != null && !memory.frozen) selectedChipFocusRequester else FocusRequester.Default }
                }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            seasons.forEach { season ->
                val isSpecial = season.name == SPECIALS_SEASON_NAME || season.indexNumber == 0
                SeasonChip(
                    label = if (isSpecial) stringResource(R.string.detail_season_specials_chip) else season.indexNumber?.toString() ?: season.name,
                    dimmed = isSpecial,
                    isSelected = season.id == selectedSeasonId,
                    onSelect = { onSelect(season.id) },
                    memory = memory,
                    key = "season:${season.id}",
                    focusRequester = if (season.id == selectedSeasonId) selectedChipFocusRequester else null,
                    bringIntoViewRequester = if (season.id == selectedSeasonId) selectedChipBringIntoViewRequester else null,
                )
            }
        }
        if (summaryItems.isNotEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                ItemBoundaryLine(
                    items = summaryItems,
                    separator = " · ",
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 10.sp, letterSpacing = (-0.02).em),
                    alignEnd = true,
                )
            }
        }
    }
}

private val SEASON_CHIP_MIN_WIDTH = 31.dp

/** The focus ring's full reach outside a chip: [tv.jellybeam.ui.cards.focusRing]'s 3dp gap + 3dp
 * stroke.
 */
private val SEASON_CHIP_RING_ROOM = 6.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonChip(
    label: String,
    dimmed: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    memory: FocusMemory,
    key: String,
    focusRequester: FocusRequester?,
    bringIntoViewRequester: BringIntoViewRequester?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val fill = if (isSelected) JellybeamTheme.Pistacchio else JellybeamTheme.Surface
    val textColor = when {
        isSelected -> JellybeamTheme.Notte
        dimmed -> JellybeamTheme.Grigio
        else -> JellybeamTheme.Panna
    }

    // docs/15 §1.2 / docs/12 §0.4: a ring on a Pistacchio fill must be Sheen, or it vanishes into
    // the fill.
    Box(modifier = Modifier.focusRing(isFocused = isFocused, cornerRadius = 50.dp, color = if (isSelected) JellybeamTheme.Sheen else JellybeamTheme.Pistacchio)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .focusKey(memory, key)
                .widthIn(min = SEASON_CHIP_MIN_WIDTH)
                .clip(RoundedCornerShape(50))
                .background(fill)
                .border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(50))
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .let { if (bringIntoViewRequester != null) it.bringIntoViewRequester(bringIntoViewRequester) else it }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
                .padding(horizontal = 10.dp, vertical = 4.5.dp),
        ) {
            BasicText(text = label, style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = textColor, fontSize = 13.sp))
        }
    }
}

// docs/19 §1.5 FIX D: "slice before render", not clipping -- while the panel is open, a below-fold
// LazyRow must show only whole cards. [CardWindow] is the sliced view a shelf renders;
// [rememberCardWindow] is how each shelf builds one.
internal class CardWindow<T>(val items: List<T>, val startIndex: Int)

/**
 * [active] false: the identity window, no slicing. [active] true: [startIndex] is latched once at
 * activation (`remember(active)`), never re-derived from [listState] while active -- re-reading it
 * would see the sliced list's reset `firstVisibleItemIndex` of 0 and re-slice from the start every
 * frame of the panel's slide. The visible count, in contrast, is recomputed every recomposition
 * since [contentWidth] itself is what's animating.
 */
@Composable
internal fun <T> rememberCardWindow(
    items: List<T>,
    listState: LazyListState,
    contentWidth: Dp,
    cardWidth: Dp,
    gap: Dp,
    active: Boolean,
): CardWindow<T> {
    if (!active) return CardWindow(items, 0)
    // Keyed on [active] alone, not [items]: a mark-watched mid-open hands a fresh list instance,
    // and re-latching then would read the already-reset index 0.
    val startIndex = remember(active) {
        val firstFullyVisible = listState.firstVisibleItemIndex + if (listState.firstVisibleItemScrollOffset > 0) 1 else 0
        firstFullyVisible.coerceIn(0, items.size)
    }
    val visibleCount = DetailFormatting.wholeCardCount(
        contentWidthDp = contentWidth.value,
        startMarginDp = PAGE_MARGIN.value,
        cardWidthDp = cardWidth.value,
        gapDp = gap.value,
    )
    // The latch outlives a refresh, so a list that shrank mid-open clamps it rather than invert the slice.
    val start = startIndex.coerceAtMost(items.size)
    val endIndex = (start + visibleCount).coerceAtMost(items.size)
    return CardWindow(items.subList(start, endIndex), start)
}

/** §3 item 7's season episode shelf: a horizontal, per-season row of [EpisodeGridCard]s (not a
 * wrapping grid).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeasonEpisodeShelf(
    episodes: List<Card>,
    isLoading: Boolean,
    onOpenDetail: (Card) -> Unit,
    memory: FocusMemory,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentWidth: Dp = 0.dp,
    active: Boolean = false,
) {
    val density = LocalDensity.current
    val episodeImageWidth = remember(density) {
        CardFormatting.bucketedImageWidth(with(density) { EPISODE_CARD_WIDTH.roundToPx() })
    }

    Box(modifier = modifier) {
        if (isLoading) {
            Row(
                modifier = Modifier.padding(horizontal = PAGE_MARGIN),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                repeat(4) { EpisodeGridSkeletonCard() }
            }
        } else if (episodes.isNotEmpty()) {
            // docs/19 §1.5 FIX D: whole episode cards only while the panel is open -- see
            // [rememberCardWindow].
            val window = rememberCardWindow(episodes, listState, contentWidth, EPISODE_CARD_WIDTH, 15.dp, active)
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
                ) {
                    itemsIndexed(window.items, key = { _, episode -> episode.id }) { _, episode ->
                        var isFocused by remember(episode.id) { mutableStateOf(false) }
                        EpisodeGridCard(
                            card = episode,
                            isFocused = isFocused,
                            imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, episodeImageWidth) },
                            onClick = { onOpenDetail(episode) },
                            modifier = Modifier.focusKey(memory, "episode:${episode.id}").onFocusChanged { focusState ->
                                isFocused = focusState.isFocused
                                // docs/13 focus-dwell preload; Rust's preload_playback no-ops on a
                                // virtual episode.
                                if (focusState.isFocused) {
                                    PreloadOnDwell.default.onCardFocused(episode.id, episode.itemType)
                                } else {
                                    PreloadOnDwell.default.onCardUnfocused(episode.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// MOVIE (spec §4) -- also this screen's fallback layout for any other non-Series/non-Episode item
// type.

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MovieDetailScreen(
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
    val context = LocalContext.current
    val detail = state.itemDetail
    // Computed inline, not remembered, so a refreshed Card recomputes instead of staying stale.
    val primaryAction = DetailFormatting.resolvePrimaryAction(card, emptyList())
    val hasPrimaryButton = primaryAction != DetailFormatting.PrimaryAction.None
    val castMembers = remember(detail) { DetailFormatting.castMembers(detail?.people.orEmpty()) }

    // [hasPrimaryButton] resolves synchronously from [card]'s own fields (no FFI round trip like
    // Series'), but a single un-retried `requestFocus()` could still lose the race against this
    // screen's layout on a freshly pushed composition -- [FocusRestorer] below is the multi-frame
    // retry guard. The door renders unconditionally, so `hasSecondary` is always true.
    val seedTarget = remember(card.id, hasPrimaryButton) {
        DetailFormatting.resolveFocusSeedTarget(hasPrimaryButton, hasSecondary = true)
    }
    val initialFocusRequester = remember(card.id) { FocusRequester() }
    val doorFocusRequester = remember(card.id) { FocusRequester() }
    // Hoisted so [FocusRestorer] and the refresh guard below share one fallback.
    val movieFallback: () -> FocusTarget? = {
        (if (seedTarget == DetailFormatting.FocusSeedTarget.PRIMARY) initialFocusRequester else doorFocusRequester).asFocusTarget()
    }
    // docs/15 §5: one shared restore mechanism. No "selected" item on this screen, so
    // [selectedKey] stays the default; restore falls straight from rule 1 to rule 3's fallback.
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        fallback = movieFallback,
        tag = "detail-movie",
    )

    val castListState = remember(card.id) { LazyListState() }
    val similarListState = remember(card.id) { LazyListState() }
    SideEffect {
        memory.scrollTo = { key ->
            when {
                key.startsWith("person:") -> {
                    val index = castMembers.indexOfFirst { it.id == key.removePrefix("person:") }
                    if (index >= 0) { castListState.scrollToItem(index); true } else false
                }
                key.startsWith("similar:") -> {
                    val index = state.similar.indexOfFirst { it.id == key.removePrefix("similar:") }
                    if (index >= 0) { similarListState.scrollToItem(index); true } else false
                }
                else -> false
            }
        }
    }
    // docs/23-detail-layout-rules.md Rule 2's full-synopsis panel.
    var synopsisOpen by remember(card.id) { mutableStateOf(false) }
    val synopsisScope = rememberCoroutineScope()

    // Same REFRESH-STEALS-FOCUS guard as [EpisodeDetailScreen] (docs/15 §3): a live refresh can
    // replace [card]/[castMembers]/[state.similar], both keyed rows, dropping the focused key with
    // nothing re-placing it. No selected item on this screen, so `restoreNow` tries
    // [FocusMemory.lastKey] then falls straight to [movieFallback].
    val lifecycleOwner = LocalLifecycleOwner.current
    var movieHasFocus by remember(card.id) { mutableStateOf(false) }
    // docs/10-perf-logging.md `detail.focus`: one-shot per push, the moment initial focus lands.
    val movieFocusMarked = remember(card.id) { booleanArrayOf(false) }
    // Same [RefreshRestoreOwner] shape as [EpisodeDetailScreen]; [refreshGuardEligible] is the
    // shared predicate.
    val refreshOwner = remember { RefreshRestoreOwner() }
    val latestIsTop by rememberUpdatedState(isTop)
    LaunchedEffect(isTop) {
        if (!isTop) refreshOwner.ownershipLost()
    }
    LaunchedEffect(panelOpen) {
        // Same shape as [EpisodeDetailScreen]'s identical hook.
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
    // P2-b: same shape as [EpisodeDetailScreen]'s identical fix.
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
    LaunchedEffect(card, castMembers, state.similar) {
        refreshOwner.onContentChanged(
            scope = synopsisScope,
            frame = { withFrameNanos {} },
            eligible = { ownsFreeze ->
                !latestPanelOpen && !drawerOpen &&
                    refreshGuardEligible(
                        isTop = latestIsTop,
                        seeded = memory.seeded,
                        resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                        hasFocus = movieHasFocus,
                        frozen = memory.frozen,
                        ownsFreeze = ownsFreeze,
                    )
            },
            restore = {
                memory.restoreNow(focusGate, fallback = movieFallback, tag = "detail-movie-refresh")
            },
        )
    }

    // BoxWithConstraints is the outermost node so [viewportHeight] is the real on-screen viewport,
    // not the unbounded height of the verticalScroll Column it constrains.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Tracks [movieHasFocus] for the refresh guard above; wraps the synopsis panel too,
                // no extra flag needed.
                .onFocusChanged {
                    movieHasFocus = it.hasFocus
                    markDetailFocusOnce(movieFocusMarked, it)
                },
        ) {
        // The frame IS the viewport -- see [frameBringIntoViewSpec].
        val movieScrollState = rememberScrollState()
        val movieBringIntoViewSpec = remember(movieScrollState) { frameBringIntoViewSpec(movieScrollState) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides movieBringIntoViewSpec) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(JellybeamTheme.Notte)
                .verticalScroll(movieScrollState),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Computed inline, not remembered, so a refreshed Card recomputes instead of
                // staying stale.
                val artSource = CardFormatting.backdropArtSource(card)
                DetailBackdropImage(card = card, artSource = artSource, height = MOVIE_BACKDROP_HEIGHT)
                BackdropScrim(
                    modifier = Modifier.fillMaxWidth().height(MOVIE_BACKDROP_HEIGHT),
                    textColumnSide = Alignment.Start,
                    horizontalStops = DETAIL_HORIZONTAL_SCRIM_STOPS,
                    verticalStops = DETAIL_VERTICAL_SCRIM_STOPS,
                )

                // docs/23-detail-layout-rules.md Rule 1: one flow column, no absolute content
                // below the backdrop/scrim pair. Bound to at least the viewport height so the spec
                // capsule's margin-top:auto (the weighted Spacer below, a direct child of this
                // Column) has real room to push against on a short page.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportHeight)
                        .padding(top = 42.dp, bottom = 32.dp)
                        // docs/19 §1.5: the page's reflow while the panel is open -- content only,
                        // never the backdrop/scrim.
                        .padding(end = contentEndInset),
                ) {
                    Column(modifier = Modifier.padding(start = PAGE_MARGIN, end = PAGE_MARGIN)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(HERO_POSTER_GAP),
                            verticalAlignment = Alignment.Top,
                        ) {
                            HeroPoster(card)
                            Column(
                                modifier = Modifier.weight(1f, fill = false).widthIn(max = 510.dp),
                                verticalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                val eyebrow = remember(card.id, state.libraryName, detail?.dateCreated) {
                                    DetailFormatting.movieEyebrow(state.libraryName, detail?.dateCreated)
                                }
                                eyebrow?.let { EyebrowText(it) }
                                BasicText(
                                    text = card.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = DETAIL_TITLE_STYLE,
                                )
                                MovieMetadataRow(card = card, detail = detail)
                                MovieActionRow(
                                    card = card,
                                    action = primaryAction,
                                    focusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.PRIMARY) initialFocusRequester else null,
                                    doorFocusRequester = if (seedTarget == DetailFormatting.FocusSeedTarget.SECONDARY) doorFocusRequester else null,
                                    memory = memory,
                                    context = context,
                                    onOpenMenu = { memory.captureInvoker(); viewModel.openMenu() },
                                )
                                OverviewBlock(
                                    text = card.overview,
                                    color = JellybeamTheme.Panna2,
                                    memory = memory,
                                    moreKey = OVERVIEW_MORE_KEY,
                                    onMore = { synopsisOpen = true },
                                )
                                CreditsLine(detail?.directors, detail?.writers, detail?.studios)
                            }
                        }
                    }

                    // The spec's margin-top: auto -- must be a direct child here, not nested inside
                    // the padded Column above.
                    Spacer(modifier = Modifier.weight(1f))

                    if (detail != null) {
                        val fileSizeField = DetailFormatting.formatFileSize(detail.sizeBytes)
                            ?.let { listOf(DetailFormatting.SpecField(it, DetailFormatting.SpecWeight.BASELINE)) }
                            .orEmpty()
                        // 8dp fixed minimum above the capsule: the weighted Spacer can compute to
                        // zero on a two-line-title page.
                        SpecStripRow(
                            itemType = card.itemType,
                            detail = detail,
                            extraFields = fileSizeField,
                            modifier = Modifier.padding(start = PAGE_MARGIN, top = 8.dp),
                        )
                    }

                    // [CastRow]'s own LazyRow already carries a PAGE_MARGIN `contentPadding`, so
                    // adding padding here too would double the left margin.
                    if (castMembers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(22.dp))
                        CastRow(
                            members = castMembers,
                            memory = memory,
                            listState = castListState,
                            showHeader = true,
                            showRole = true,
                            contentWidth = contentWidth,
                            active = cardWindowActive,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // docs/19 §1.5: a sibling of the inset frame above, so it carries the panel inset
            // itself.
            if (state.similarLoaded && state.similar.isNotEmpty()) {
                Box(modifier = Modifier.padding(top = SECTION_TOP_GAP, end = contentEndInset)) {
                    SimilarRow(
                        items = state.similar,
                        onOpenDetail = onOpenDetail,
                        memory = memory,
                        listState = similarListState,
                        contentWidth = contentWidth,
                        active = cardWindowActive,
                    )
                }
            }

            Box(modifier = Modifier.height(32.dp))
        }
        }
        if (synopsisOpen) {
            SynopsisPanel(
                title = card.name,
                overview = card.overview.orEmpty(),
                open = synopsisOpen,
                onClose = {
                    synopsisOpen = false
                    synopsisScope.launch { memory.placeByKeys(listOf(OVERVIEW_MORE_KEY), focusGate) }
                },
                isTop = isTop,
                focusGate = focusGate,
            )
        }
        }
    }
}

/**
 * The Movie page's scroll rule: the frame is the first viewport-height of content, so a target
 * inside it (pills, MORE, cast) scrolls the page back to the top, never partway. A target below
 * the frame scrolls only as far as needed. Compose's TV default pivots every focused child to a
 * fixed viewport fraction instead, which dragged the title away the moment MORE took focus.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun frameBringIntoViewSpec(scrollState: ScrollState): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val contentTop = offset + scrollState.value
        return when {
            contentTop + size <= containerSize -> -scrollState.value.toFloat()
            offset < 0f -> offset
            offset + size > containerSize -> offset + size - containerSize
            else -> 0f
        }
    }
}

/** §4 item 4's metadata row: "{year} · {runtime}", a boxed rating badge, then up to 3 genre chips.
 */
@Composable
private fun MovieMetadataRow(card: Card, detail: ItemDetail?) {
    // Computed inline, not remembered, so a refreshed Card recomputes instead of staying stale.
    val metaLine = DetailFormatting.movieMetaLine(card.productionYear, card.runtimeTicks)
    if (metaLine == null && detail?.officialRating.isNullOrBlank() && detail?.genres.isNullOrEmpty()) return

    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        metaLine?.let {
            BasicText(text = it, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 13.5.sp))
        }
        detail?.officialRating?.takeIf { it.isNotBlank() }?.let { RatingBadge(it) }
        detail?.genres?.filter { it.isNotBlank() }?.take(3)?.forEach { GenreChip(it) }
    }
}

@Composable
private fun RatingBadge(rating: String) {
    Box(modifier = Modifier.border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
        BasicText(text = rating, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 9.5.sp))
    }
}

@Composable
private fun GenreChip(name: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(JellybeamTheme.Surface.copy(alpha = 0.9f))
            .border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        BasicText(text = name, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 11.sp))
    }
}

/** §4 item 5's Movie button row: Play/Resume, then the `···` door at position two (docs/19 rules
 * 1-2). The door renders even with no primary action, so this row is never empty.
 */
@Composable
private fun MovieActionRow(
    card: Card,
    action: DetailFormatting.PrimaryAction,
    focusRequester: FocusRequester?,
    doorFocusRequester: FocusRequester?,
    memory: FocusMemory,
    context: android.content.Context,
    onOpenMenu: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (action != DetailFormatting.PrimaryAction.None) {
            val label = when (action) {
                is DetailFormatting.PrimaryAction.Playable -> action.label
                is DetailFormatting.PrimaryAction.Unavailable -> action.label
                DetailFormatting.PrimaryAction.None -> ""
            }
            PrimaryPillButton(
                label = label,
                enabled = action is DetailFormatting.PrimaryAction.Playable,
                onClick = {
                    if (action is DetailFormatting.PrimaryAction.Playable) {
                        PerfLog.markStartup("playback.click")
                        PlaybackActivity.launch(context, action.targetId)
                    }
                },
                memory = memory,
                key = "action:primary",
                focusRequester = focusRequester,
                preloadItemId = (action as? DetailFormatting.PrimaryAction.Playable)?.targetId,
            )
        }
        MenuDoorPill(
            onOpen = onOpenMenu,
            memory = memory,
            focusRequester = doorFocusRequester,
        )
    }
}

// Shared building blocks

/**
 * docs/23-detail-layout-rules.md Rule 2's hard clamp: 4 lines, ellipsized, with a focusable
 * [MoreStop] appearing only when the clamp actually bit (`hasVisualOverflow`, not a line-count
 * guess).
 */
@Composable
private fun OverviewBlock(text: String?, color: Color, memory: FocusMemory, moreKey: String, onMore: () -> Unit) {
    if (text.isNullOrBlank()) return
    var clamped by remember(text) { mutableStateOf(false) }
    // The stop is part of the overview, not a sibling row: 4dp under the text, so the caller's own
    // row gap applies once.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            text = text,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { clamped = it.hasVisualOverflow },
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = color, fontSize = 12.5.sp, lineHeight = 19.sp),
        )
        if (clamped) {
            MoreStop(memory = memory, moreKey = moreKey, onMore = onMore)
        }
    }
}

/** [OverviewBlock]'s own "MORE ↓" focus stop -- opens [SynopsisPanel]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoreStop(memory: FocusMemory, moreKey: String, onMore: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .focusKey(memory, moreKey)
            .focusRing(isFocused = isFocused, cornerRadius = 4.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onMore)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        BasicText(
            text = stringResource(R.string.detail_more),
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 9.sp, letterSpacing = (-0.02).em),
        )
    }
}

/**
 * docs/23-detail-layout-rules.md Rule 3: director/writer/studio as one non-wrapping prose line.
 * Each segment gets its own `weight(1f, fill = false)` so a long name list shrinks and ellipsizes
 * instead of reflowing a sibling or overflowing the row.
 */
@Composable
private fun CreditsLine(directors: List<String>?, writers: List<String>?, studios: List<String>?) {
    val directorsLine = remember(directors) { DetailFormatting.peopleLine(directors.orEmpty()) }
    val writersLine = remember(writers) { DetailFormatting.peopleLine(writers.orEmpty()) }
    val studioLine = remember(studios) { DetailFormatting.studioSummary(studios.orEmpty())?.uppercase(Locale.US) }
    if (directorsLine == null && writersLine == null && studioLine == null) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        var needsSeparator = false
        directorsLine?.let { names ->
            if (needsSeparator) CreditsSeparator()
            needsSeparator = true
            Row(modifier = Modifier.weight(creditsWeight(names), fill = false), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringResource(R.string.detail_directed_by),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 8.5.sp, letterSpacing = (-0.02).em),
                )
                Box(modifier = Modifier.width(6.dp))
                BasicText(
                    text = names,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 11.sp),
                )
            }
        }
        writersLine?.let { names ->
            if (needsSeparator) CreditsSeparator()
            needsSeparator = true
            Row(modifier = Modifier.weight(creditsWeight(names), fill = false), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringResource(R.string.detail_written_by),
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 8.5.sp, letterSpacing = (-0.02).em),
                )
                Box(modifier = Modifier.width(6.dp))
                BasicText(
                    text = names,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 11.sp),
                )
            }
        }
        studioLine?.let { studio ->
            if (needsSeparator) CreditsSeparator()
            BasicText(
                text = studio,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(creditsWeight(studio), fill = false),
                style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 8.5.sp),
            )
        }
    }
}

/** A segment's row share is proportional to its text length: equal weights would hand a short name
 * a third of the row it can't use, and Compose never redistributes a weighted child's unused share.
 */
private fun creditsWeight(text: String): Float = (text.length + 12).toFloat()

@Composable
private fun CreditsSeparator() {
    BasicText(text = " │ ", style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Hairline, fontSize = 9.sp))
}

/**
 * The full-synopsis panel [MoreStop] opens: a full-screen scrim, D-pad Up/Down scrolls the
 * overview, Left/Right/Select are swallowed, and Back closes it -- [onClose] returns focus to the
 * [MoreStop] that opened it via [tv.jellybeam.ui.focus.placeByKeys].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SynopsisPanel(
    title: String,
    overview: String,
    open: Boolean,
    onClose: () -> Unit,
    isTop: Boolean,
    focusGate: MutableState<Boolean>,
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val requester = remember { FocusRequester() }
    val density = LocalDensity.current
    val scrollStepPx = with(density) { 80.dp.toPx() }

    LaunchedEffect(open) {
        if (open) requestFocusWithRetry(focusGate) { requester.requestFocus() }
    }
    BackHandler(enabled = isTop && open) { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte.copy(alpha = 0.88f))
            .focusRequester(requester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { scope.launch { scrollState.animateScrollBy(-scrollStepPx) }; true }
                    Key.DirectionDown -> { scope.launch { scrollState.animateScrollBy(scrollStepPx) }; true }
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter -> true
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 40.dp, top = 42.dp)
                .width(620.dp),
        ) {
            BasicText(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 18.sp),
            )
            Box(modifier = Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
                    .padding(bottom = 40.dp),
            ) {
                BasicText(
                    text = overview,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 13.sp, lineHeight = 20.sp),
                )
            }
        }
    }
}

/**
 * The full-bleed backdrop image every screen above uses. [CardArtImage]'s no-art fallback
 * ([tv.jellybeam.ui.cards.PlaceholderTile]) renders the item's name centered on the tile, which would
 * duplicate the real title already overlaid in the text column -- so a missing backdrop renders a
 * flat panel with no text instead, same special-case [HeroPoster] uses.
 */
@Composable
internal fun DetailBackdropImage(card: Card, artSource: ArtSource, height: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(height)) {
        // Captured into a local val before use inside the nested remember{} lambda:
        // BoxWithConstraints'
        // `maxWidth` is an implicit scope receiver a K2 nested lambda can't resolve directly.
        val backdropWidthDp = maxWidth
        val widthPx = remember(backdropWidthDp, density) {
            CardFormatting.bucketedImageWidth(with(density) { backdropWidthDp.roundToPx() })
        }
        if (artSource == ArtSource.None) {
            Box(modifier = Modifier.fillMaxSize().background(JellybeamTheme.SurfacePanel))
        } else {
            CardArtImage(
                source = artSource,
                imageUrl = { itemId, kind, tag ->
                    // docs/10-perf-logging.md `detail.backdrop`: arms on the exact URL this
                    // request will resolve to, so its own successful Coil decode -- reported
                    // globally by PerfImageEventListener -- fires the mark.
                    AppGraph.gateway.imageUrl(itemId, kind, tag, widthPx)
                        ?.also { PerfLog.armDetailImageMark("detail.backdrop", it) }
                },
                itemName = card.name,
                contentAlpha = if (card.isVirtual) 0.4f else 1f,
                blurhash = card.blurhash,
                placeholderDimAlpha = BACKDROP_PLACEHOLDER_DIM,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Series/Movie hero poster: 2:3, 3dp radius, drop shadow. Missing art is a flat SURFACE_PANEL rect
 * -- no name text, same reasoning as [DetailBackdropImage].
 */
@Composable
private fun HeroPoster(card: Card, modifier: Modifier = Modifier) {
    // Computed inline, not remembered, so a refreshed Card recomputes instead of staying stale.
    val artSource = DetailFormatting.heroPosterArtSource(card)
    val density = LocalDensity.current
    val posterWidthPx = remember(density) { CardFormatting.bucketedImageWidth(with(density) { HERO_POSTER_WIDTH.roundToPx() }) }
    // Home's POSTER shelf and the library grid both request this item's poster at the shared 240
    // bucket (docs/07 §1); reusing that URL as a memory-cache key shows the already-resident
    // bitmap immediately instead of a blurhash while the sharper 360 rendition loads in.
    val placeholderMemoryCacheKey = remember(artSource) {
        artSource?.let { source ->
            CardFormatting.resolveArtUrl(source) { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, 240u) }
        }?.let { MemoryCache.Key(it) }
    }

    Box(
        modifier = modifier
            .size(HERO_POSTER_WIDTH, HERO_POSTER_HEIGHT)
            .clip(RoundedCornerShape(HERO_POSTER_RADIUS)),
    ) {
        if (artSource == null || artSource == ArtSource.None) {
            Box(modifier = Modifier.fillMaxSize().background(JellybeamTheme.SurfacePanel))
        } else {
            CardArtImage(
                source = artSource,
                imageUrl = { itemId, kind, tag ->
                    // docs/10-perf-logging.md `detail.poster`: same arm-by-URL shape as
                    // [DetailBackdropImage]'s `detail.backdrop`.
                    AppGraph.gateway.imageUrl(itemId, kind, tag, posterWidthPx)
                        ?.also { PerfLog.armDetailImageMark("detail.poster", it) }
                },
                itemName = card.name,
                contentAlpha = 1f,
                blurhash = card.blurhash,
                placeholderDimAlpha = BACKDROP_PLACEHOLDER_DIM,
                placeholderMemoryCacheKey = placeholderMemoryCacheKey,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun EyebrowText(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Pistacchio, fontSize = 11.sp, letterSpacing = (-0.02).em),
    )
}

/** A resumable item's progress bar + mono "{n}m left" label. */
@Composable
private fun ResumeProgressRow(fraction: Float, remainingLabel: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(JellybeamTheme.ProgressTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(JellybeamTheme.Pistacchio),
            )
        }
        remainingLabel?.let {
            BasicText(text = it, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna2, fontSize = 11.sp))
        }
    }
}

@Composable
private fun PrimaryPillButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    memory: FocusMemory,
    key: String,
    focusRequester: FocusRequester? = null,
    preloadItemId: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(modifier = Modifier.focusRing(isFocused = enabled && isFocused, cornerRadius = ACTION_BUTTON_HEIGHT / 2, color = if (enabled) JellybeamTheme.Sheen else JellybeamTheme.Pistacchio)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .focusKey(memory, key)
                .heightIn(min = ACTION_BUTTON_HEIGHT)
                .widthIn(min = ACTION_BUTTON_MIN_WIDTH)
                .clip(RoundedCornerShape(50))
                .background(if (enabled) JellybeamTheme.Pistacchio else JellybeamTheme.Surface)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { focusState ->
                    preloadItemId?.let { itemId ->
                        if (enabled && focusState.isFocused) {
                            PreloadOnDwell.default.onPlayableActionFocused(itemId)
                        } else {
                            PreloadOnDwell.default.onCardUnfocused(itemId)
                        }
                    }
                }
                .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
                .padding(horizontal = ACTION_BUTTON_HPADDING),
        ) {
            BasicText(
                text = label,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = if (enabled) JellybeamTheme.Notte else JellybeamTheme.Grigio, fontSize = 15.sp),
            )
        }
    }
}

/** [DetailFormatting.SpecWeight]'s three tier colors -- one place, so a call site never picks. */
private fun specWeightColor(weight: DetailFormatting.SpecWeight): Color = when (weight) {
    DetailFormatting.SpecWeight.BASELINE -> JellybeamTheme.Grigio
    DetailFormatting.SpecWeight.NOTABLE -> JellybeamTheme.Panna
    DetailFormatting.SpecWeight.BEST_IN_CLASS -> JellybeamTheme.Pistacchio
}

/**
 * §2 item 4 / §4 item 7's spec strip: one non-wrapping pill of Martian Mono values, individually
 * colored by [DetailFormatting.classify]. Renders nothing for a Series or before [detail]
 * resolves. [extraFields] appends after the base fields (the Movie screen's trailing file-size
 * cell). docs/23-detail-layout-rules.md Rule 4: an outlined capsule, not filled.
 */
@Composable
private fun SpecStripRow(itemType: String, detail: ItemDetail?, extraFields: List<DetailFormatting.SpecField>, modifier: Modifier = Modifier) {
    if (itemType == SERIES_ITEM_TYPE || detail == null) return
    val fields = remember(detail, extraFields) { DetailFormatting.specStripFields(detail) + extraFields }
    if (fields.isEmpty()) return

    FlowRow(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, JellybeamTheme.Hairline, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        fields.forEachIndexed { index, field ->
            Row {
                if (index > 0) {
                    BasicText(
                        text = " │ ",
                        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Hairline, fontSize = 9.sp),
                    )
                }
                BasicText(
                    text = field.value,
                    style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = specWeightColor(field.weight), fontSize = 9.sp),
                )
            }
        }
    }
}

// docs/23-detail-layout-rules.md Rule 5's own [CastRow] figure: 64dp
// avatar + 8dp gap + ~14dp name line + 2dp gap + ~12dp role line.
private val CAST_ROW_FADE_HEIGHT = 100.dp
private val EDGE_FADE_WIDTH = 48.dp
private val EDGE_FADE_LEFT_BRUSH = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(JellybeamTheme.Notte, Color.Transparent))
private val EDGE_FADE_RIGHT_BRUSH = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, JellybeamTheme.Notte))

@Composable
private fun EdgeFadedLazyRow(listState: LazyListState, height: Dp, content: @Composable () -> Unit) {
    val showLeftFade by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val showRightFade by remember { derivedStateOf { listState.canScrollForward } }

    Box(modifier = Modifier.fillMaxWidth()) {
        content()
        if (showLeftFade) {
            Box(modifier = Modifier.align(Alignment.CenterStart).width(EDGE_FADE_WIDTH).height(height).background(EDGE_FADE_LEFT_BRUSH))
        }
        if (showRightFade) {
            Box(modifier = Modifier.align(Alignment.CenterEnd).width(EDGE_FADE_WIDTH).height(height).background(EDGE_FADE_RIGHT_BRUSH))
        }
    }
}

@Composable
private fun SectionHeader(textRes: Int) {
    // At the page margin, where the row's content padding puts its first card and CastRow puts
    // its own label.
    BasicText(
        text = stringResource(textRes),
        modifier = Modifier.padding(start = PAGE_MARGIN),
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Panna, fontSize = 20.sp),
    )
}

// docs/23-detail-layout-rules.md Rule 5's own [CastRow] figure.
private val CAST_CARD_WIDTH = 84.dp
private val CAST_AVATAR_SIZE = 64.dp
private val CAST_ROW_GAP = 22.dp

/**
 * The Cast row: one treatment for all three screens (docs/23-detail-layout-rules.md Rule 5's
 * fixed-size, truncating cards). [showHeader] is Series/Movie's "CAST" label; [showRole] is
 * Series/Movie's role line (Episode has neither).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CastRow(
    members: List<PersonInfo>,
    memory: FocusMemory,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showRole: Boolean = true,
    showHeader: Boolean = false,
    // docs/19 §1.5 FIX D: defaults mean "no slicing", keeping any other/future caller safe.
    contentWidth: Dp = 0.dp,
    active: Boolean = false,
) {
    if (members.isEmpty()) return
    // FIX D: whole cast cards only while the panel is open (see [rememberCardWindow]).
    // [window.startIndex] widens each rendered item's key so scroll-position-by-key survives a
    // close; [itemRequesters] stays keyed by the composed (rendered-relative) index since `enter`
    // reads [listState.firstVisibleItemIndex] in that same space.
    val window = rememberCardWindow(members, listState, contentWidth, CAST_CARD_WIDTH, CAST_ROW_GAP, active)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeader) {
            BasicText(
                text = stringResource(R.string.detail_cast).uppercase(Locale.US),
                modifier = Modifier.padding(start = PAGE_MARGIN),
                style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 10.sp, letterSpacing = (-0.02).em),
            )
        }
        // Same shelf scroll rule as the episode shelf and Home's rows: the focused portrait pins
        // at the page margin. docs/15 §2: entering this group lands on the row's first visible
        // portrait, not the spatially nearest one; yields during a programmatic restore
        // ([memory.frozen]) so a remembered `person:<id>` wins.
        val itemRequesters = remember { mutableMapOf<Int, FocusRequester>() }
        EdgeFadedLazyRow(listState = listState, height = CAST_ROW_FADE_HEIGHT) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(CAST_ROW_GAP),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
                    modifier = Modifier
                        .focusProperties {
                            enter = {
                                if (memory.frozen) FocusRequester.Default else itemRequesters[listState.firstVisibleItemIndex] ?: FocusRequester.Default
                            }
                        }
                        .focusGroup(),
                ) {
                    itemsIndexed(window.items, key = { index, person -> "${window.startIndex + index}-${person.id}" }) { index, person ->
                        val requester = remember { FocusRequester() }
                        DisposableEffect(index, requester) {
                            itemRequesters[index] = requester
                            onDispose { if (itemRequesters[index] === requester) itemRequesters.remove(index) }
                        }
                        CastPortrait(
                            person = person,
                            memory = memory,
                            key = "person:${person.id}",
                            showRole = showRole,
                            focusRequester = requester,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastPortrait(person: PersonInfo, memory: FocusMemory, key: String, showRole: Boolean, focusRequester: FocusRequester) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // The whole card is the focus target (the ring still draws on the portrait): the shelf scroll
    // rule pins the focused node's leading edge at the page margin, and a centered portrait would
    // nudge the row.
    Column(
        modifier = Modifier
            .width(CAST_CARD_WIDTH)
            .focusKey(memory, key)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val density = LocalDensity.current
        val avatarWidthPx = remember(density) { CardFormatting.bucketedImageWidth(with(density) { CAST_AVATAR_SIZE.roundToPx() }) }
        Box(
            modifier = Modifier
                .size(CAST_AVATAR_SIZE)
                .focusRing(isFocused = isFocused, cornerRadius = CAST_AVATAR_SIZE / 2)
                .clip(CircleShape),
        ) {
            CardArtImage(
                source = ArtSource.Own(person.id!!, person.primaryImageTag!!, ImageKind.PRIMARY),
                imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, avatarWidthPx) },
                itemName = person.name.orEmpty(),
                contentAlpha = 1f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(modifier = Modifier.height(8.dp))
        BasicText(
            text = person.name.orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 11.sp, textAlign = TextAlign.Center),
        )
        if (showRole) {
            person.role?.takeIf { it.isNotBlank() }?.let { role ->
                Box(modifier = Modifier.height(2.dp))
                BasicText(
                    text = role,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 10.sp, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

private val SIMILAR_ROW_FADE_HEIGHT = POSTER_CELL_WIDTH * 1.5f + 40.dp

// docs/19 §1.5 FIX D: this row's own card width + gap, for [rememberCardWindow].
internal val SIMILAR_CARD_GAP = 16.dp

/** The "Similar Titles" rail (Series and Movie only -- an Episode page never fetches it, see
 * [DetailViewModel]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimilarRow(
    items: List<Card>,
    onOpenDetail: (Card) -> Unit,
    memory: FocusMemory,
    listState: LazyListState = rememberLazyListState(),
    // docs/19 §1.5 FIX D: defaults mean "no slicing" -- see [CastRow]'s doc on the same pair.
    contentWidth: Dp = 0.dp,
    active: Boolean = false,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val posterWidthPx = remember(density) { CardFormatting.bucketedImageWidth(with(density) { POSTER_CELL_WIDTH.roundToPx() }) }
    // FIX D: whole poster cards only while the panel is open.
    val window = rememberCardWindow(items, listState, contentWidth, POSTER_CELL_WIDTH, SIMILAR_CARD_GAP, active)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(R.string.detail_similar_titles)
        // Shelf scroll rule, same reason as [CastRow].
        EdgeFadedLazyRow(listState = listState, height = SIMILAR_ROW_FADE_HEIGHT) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
                LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(SIMILAR_CARD_GAP), contentPadding = PaddingValues(horizontal = PAGE_MARGIN)) {
                    itemsIndexed(window.items, key = { _, similarCard -> similarCard.id }) { _, item ->
                        var isFocused by remember(item.id) { mutableStateOf(false) }
                        PosterCard(
                            card = item,
                            isFocused = isFocused,
                            imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, posterWidthPx) },
                            onClick = { onOpenDetail(item) },
                            modifier = Modifier.focusKey(memory, "similar:${item.id}").onFocusChanged { isFocused = it.isFocused },
                        )
                    }
                }
            }
        }
    }
}

/** docs/10 `detail.focus`: one mark per push, on the first focus gain. */
internal fun markDetailFocusOnce(marked: BooleanArray, state: FocusState) {
    if (state.hasFocus && !marked[0]) {
        marked[0] = true
        PerfLog.markDetailPhase("detail.focus")
    }
}
