package tv.jellybeam.ui.home.classic

import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.perf.PerfLog
import tv.jellybeam.player.PlaybackActivity
import tv.jellybeam.ui.cards.BACKDROP_PLACEHOLDER_DIM
import tv.jellybeam.ui.cards.CARD_TITLE_BLOCK_HEIGHT
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.POSTER_CELL_WIDTH
import tv.jellybeam.ui.cards.PosterCard
import tv.jellybeam.ui.cards.PreloadOnDwell
import tv.jellybeam.ui.cards.ResumeCard
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec
import tv.jellybeam.ui.cards.rememberSkeletonPulseAlpha
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.FocusTarget
import tv.jellybeam.ui.focus.RefreshRestoreOwner
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.refreshGuardEligible
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.focus.restoreNow
import tv.jellybeam.ui.home.common.EmptyLibraryState
import tv.jellybeam.ui.home.common.HomeClock
import tv.jellybeam.ui.home.common.HomeMarks
import tv.jellybeam.ui.home.common.HomeSyncStatusPill
import tv.jellybeam.ui.nav.LocalDrawerFocusCoordinator
import tv.jellybeam.ui.theme.BackdropScrim
import tv.jellybeam.ui.theme.JellybeamHeaderLockup
import uniffi.jellybeam_core.Card

/** The cross-screen safe area. */
private val PAGE_MARGIN = 40.dp

/** Top inset for the scrolling column's content ([HomeMasthead] renders above this). */
private val TOP_SAFE_AREA = 22.dp

/** Tightened so more shelves/cards fit per screen without shrinking cards below
 * [POSTER_CELL_WIDTH]/`CardFormatting.resumeCardWidthDp`.
 */
private val CELL_GAP = 15.dp
private val SHELF_GAP = 14.dp

/** [ResumeCard]/[tv.jellybeam.ui.cards.PosterCard]'s shared text-block height below their art, so
 * [EdgeFadedRow]'s left-gutter fade sizes to a scrolled-off card's full height, not just its art.
 */
private val CARD_TEXT_BLOCK_HEIGHT = 4.dp + CARD_TITLE_BLOCK_HEIGHT + 16.dp

/** Bottom clearance for the scrolling Column beyond [SHELF_GAP]: a focused card's ring/scale
 * extends past its own measured bounds (docs/07 §4/§6), so the last shelf needs more room.
 */
private val BOTTOM_CONTENT_PADDING = 40.dp

/** docs/07 §1: hero banner height, fixed dp. */
private val HERO_HEIGHT = 280.dp

/** docs/07 §6: hero inset, the shared [PAGE_MARGIN]. */
private val HERO_INSET = PAGE_MARGIN

/** Text column's offset from the hero's own top edge, not the screen's. */
private val HERO_TEXT_TOP_OFFSET = 93.dp
private val HERO_BUTTON_GAP = 16.dp
private const val HERO_TEXT_COLUMN_WIDTH_FRACTION = 0.6f

/** Resume progress bar: fixed width, not `fillMaxWidth`. */
private val HERO_PROGRESS_BAR_WIDTH = 230.dp
private val HERO_PROGRESS_BAR_HEIGHT = 4.dp
private val HERO_PROGRESS_LABEL_GAP = 10.dp

/** docs/07 §4: gradient overlay at a shelf's trailing/right scroll edge -- see
 * [LEFT_GUTTER_FADE_WIDTH] for why the leading edge differs.
 */
private val EDGE_FADE_WIDTH = 48.dp

/**
 * Exactly [PAGE_MARGIN] wide -- full [JellybeamTheme.Notte] at x0, transparent at x40dp -- covering
 * precisely the safe-area gutter a scrolled-off card's cropped edge sits in. The focused card
 * rests flush at the [PAGE_MARGIN] inset (see [tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec]),
 * so the wider [EDGE_FADE_WIDTH] would dim its leading edge at rest; the trailing edge keeps it.
 */
private val LEFT_GUTTER_FADE_WIDTH = PAGE_MARGIN

/**
 * Hoisted to file scope: these are fixed [JellybeamTheme] colors, so allocating a fresh [Brush] on
 * every recomposition ([EdgeFadedRow]'s edge fades flip on every scroll tick) was pure waste.
 * [tv.jellybeam.ui.theme.BackdropScrim] provides the hero's own two-layer wash;
 * [MASTHEAD_GRADIENT_BRUSH] is a separate, shorter gradient for the floating wordmark.
 */
private val MASTHEAD_GRADIENT_BRUSH = Brush.verticalGradient(
    0f to JellybeamTheme.Notte.copy(alpha = 0.9f),
    1f to Color.Transparent,
)

/** [HeroBanner]'s art Crossfade duration -- see its own call-site comment (hero-swap flash fix). */
private const val HERO_ART_CROSSFADE_MS = 300

/**
 * Fades the whole hero+shelves block in as one unit the moment the first snapshot lands, no
 * per-shelf stagger. Duration/easing match [tv.jellybeam.ui.detail.DetailScreen]'s `revealHeight`
 * convention. Read inside the `graphicsLayer` lambda (draw phase), not in composition.
 */
private const val CONTENT_REVEAL_MS = 220
private val EDGE_FADE_LEFT_BRUSH = Brush.horizontalGradient(listOf(JellybeamTheme.Notte, Color.Transparent))
private val EDGE_FADE_RIGHT_BRUSH = Brush.horizontalGradient(listOf(Color.Transparent, JellybeamTheme.Notte))

/**
 * A shelf-shaped skeleton (hero block + pulsing tile rows, geometry borrowed from
 * [HeroBanner]/[Shelf]) fills the void during [tv.jellybeam.ui.home.common.HomeChrome.isLoading], cross-fading against
 * [contentRevealAlpha] as its exact inverse. Tile geometry reuses
 * [CardFormatting.resumeRowHeightDp] and [POSTER_CELL_WIDTH] so nothing visibly reflows when real
 * shelves replace the placeholders.
 */
private const val SKELETON_SHELF_COUNT = 2
private val SKELETON_TITLE_WIDTH = 140.dp
private val SKELETON_TITLE_HEIGHT = 18.dp
private val SKELETON_TITLE_RADIUS = 4.dp
/** Matches [tv.jellybeam.ui.cards.ArtBox]'s `ART_RADIUS`, duplicated since that one is private to
 * CardArt.kt.
 */
private val SKELETON_ART_RADIUS = 2.dp

/**
 * docs/07-home-browse-behavior.md §1-2: the core's shelves in its order ([ClassicContent.shelves]),
 * plus §1's hero banner ([ClassicContent.hero]) and §4's
 * per-shelf edge-fade overlays ([EdgeFadedRow]).
 */
// OptIn: LocalBringIntoViewSpec (the hero region's no-op focus-scroll override) is still
// experimental foundation API in 1.10.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClassicHomeScreen(
    onOpenDetail: (Card) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate: closed while hidden/transitioning; opened before each explicit
     * focus placement. See [tv.jellybeam.MainActivity.RetainedScreenLayer].
     */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: ClassicHomeViewModel = viewModel(
        factory = ClassicHomeViewModelFactory(AppGraph.gateway, launchWarmup = AppGraph.launchWarmup),
    ),
) {
    val feedState by viewModel.state.collectAsState()
    val chrome = feedState.chrome
    val state = feedState.content
    // Hoisted here rather than down by [shelves] below, so the becoming-top restore effect's
    // hero/no-record branch (which needs to know whether a hero exists before deciding whether to
    // wait on [heroPositioned]) can reference it -- a local `val` isn't visible above its
    // declaration.
    val hero = state.hero
    // Same signal as [shelf0FirstCellPositioned] below, but for the hero region: set by the hero
    // wrapper Box's `onGloballyPositioned` once actually measured/placed. Hoisted with [hero] for
    // the same reason; the becoming-top restore effect's hero/no-record branch reads it.
    var heroPositioned by remember { mutableStateOf(false) }
    // One LazyListState per shelf, keyed by shelf title so a shelf's horizontal scroll position is
    // independent of its position in the shelf list. Declared up here with the other
    // restore-effect dependencies: the becoming-NON-top branch below resets stale shelf offsets
    // while this layer is hidden.
    val shelfListStates = remember { mutableStateMapOf<String, LazyListState>() }

    // This screen enters composition exactly once per signed-in session (see `JellybeamRoot`), so
    // [isTop] -- true only while Home is the visible top of the stack -- is the becoming-top
    // signal, not re-entering composition.
    //
    // Focus: this transition is also the one place a hidden Home's focus needs to be explicitly
    // reclaimed -- Compose doesn't auto-restore focus to a retained subtree just because it becomes
    // visible. docs/15-focus-and-selection.md §2-§5's shared [tv.jellybeam.ui.focus.FocusMemory]/
    // [tv.jellybeam.ui.focus.FocusRestorer] is the becoming-top restore mechanism; [homeFallback] (§2
    // rule 3) is shared with the drawer-close hook below so both fall back identically.
    val homeGroupFocusRequester = remember { FocusRequester() }
    var homeWasTop by remember { mutableStateOf(isTop) }
    // docs/15-focus-and-selection.md §3: whether any descendant of the root focus group holds
    // focus; `false` after a refresh disposes the focused shelf cell with nothing re-placing it.
    var homeHasFocus by remember { mutableStateOf(false) }

    val memory = rememberFocusMemory()
    val heroPrimaryFocusRequester = remember { FocusRequester() }
    val initialFocusRequester = remember { FocusRequester() }
    // Hoisted so the drawer-close restore hook below can `launch` from a plain callback, not a
    // suspend `LaunchedEffect` body.
    val coroutineScope = rememberCoroutineScope()
    val homeFallback: () -> FocusTarget? = {
        if (hero != null) heroPrimaryFocusRequester.asFocusTarget() else initialFocusRequester.asFocusTarget()
    }

    // Home's restore path also runs from the drawer's optional close hook (see
    // [tv.jellybeam.ui.nav.DrawerFocusCoordinator]) so closing the drawer without navigating away also
    // returns focus to the remembered cell; `null` on a screen not mounted under a
    // [tv.jellybeam.ui.nav.NavDrawerHost]. Not an `isTop` transition, so it calls `memory.restoreNow`
    // directly rather than going through `FocusRestorer`'s becoming-top effect. A `SideEffect`, not
    // `remember`/`LaunchedEffect`, so the closures reference the current recomposition's state.
    val drawerFocusCoordinator = LocalDrawerFocusCoordinator.current
    // Guards a rapid open/close/open/close double-tap from launching a second restore coroutine
    // while the first is mid-flight.
    var drawerRestoreJob by remember { mutableStateOf<Job?>(null) }
    // docs/15-focus-and-selection.md §3: [RefreshRestoreOwner] owns the refresh guard's (below)
    // `restoreNow` coroutine and its freeze.
    val refreshOwner = remember { RefreshRestoreOwner() }
    if (drawerFocusCoordinator != null) {
        SideEffect {
            drawerFocusCoordinator.onDrawerOpened = {
                // Same freeze as a becoming-non-top transition: a stray default-focus grab while
                // the drawer animates must not corrupt the record before this file's restore reads
                // it. `releaseFreeze` cancels any in-flight refresh restore first, so this freeze
                // is unambiguously the drawer's.
                refreshOwner.ownershipLost(releaseFreeze = true)
                memory.frozen = true
            }
            drawerFocusCoordinator.onDrawerClosed = { navigatingAway ->
                if (navigatingAway) {
                    // Keep memory frozen; the incoming screen owns placement.
                    true
                } else {
                    drawerRestoreJob?.cancel()
                    drawerRestoreJob = coroutineScope.launch {
                        memory.restoreNow(focusGate, fallback = homeFallback, tag = "home-drawer")
                    }
                    true
                }
            }
        }
    }

    // refreshNow on every return to Home -- a stale-to-fresh Continue-Watching/hero swap must not
    // happen live, in full view. Independent of `FocusRestorer`'s focus-placement effect: refresh
    // is data, not focus.
    LaunchedEffect(isTop) {
        if (isTop && !homeWasTop) {
            viewModel.refreshNow()
        } else if (!isTop && homeWasTop) {
            // A shelf's horizontal offset is retained across push/pop, but focus only re-enters
            // one shelf (or the hero); every other shelf keeps a scroll position with no focus
            // anchor, so its first cards sit half off-screen. Reset every shelf except the one
            // focus will restore into to item 0 here (invisible while hidden) rather than on the
            // way back in. [memory.lastKey] is parsed back out of `"shelf:<id>/card:<id>"`.
            //
            // `.entries.toList()` snapshots the map before iterating: a structural change to the
            // live SnapshotStateMap during this loop's suspension points would throw
            // ConcurrentModificationException. CancellationException is rethrown, not swallowed, so
            // a becoming-top pass cancelling this same effect mid-loop actually stops it here.
            // [shelfListStates] only holds entries for mounted shelves (docs/07 §1), so a shelf
            // progressive mounting hasn't reached yet is never visited here -- nothing to reset.
            val targetShelfId = memory.lastKey
                ?.takeIf { it.startsWith("shelf:") }
                ?.removePrefix("shelf:")
                ?.substringBefore("/card:")
            shelfListStates.entries.toList().forEach { (shelfId, listState) ->
                if (shelfId != targetShelfId) {
                    try {
                        listState.scrollToItem(0)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best-effort snap; a shelf that can't be reset keeps its stale offset.
                    }
                }
            }
        }
        homeWasTop = isTop
    }

    // docs/16-library-sort-filter.md §4.6, docs/17-mini-player.md §6: feeds this screen's
    // top-of-stack-and-resumed signal into HomeFeed's changeRefreshScheduler and the
    // pollSyncing/pollSyncStatus gating.
    LaunchedEffect(isTop) {
        viewModel.setActive(isTop)
    }

    // [refreshOwner] must not outlive Home's ownership of focus, lost the moment it's no longer
    // top. Separate from the `homeWasTop`-transition effect above so this reruns on every `isTop`
    // value. `releaseFreeze` stays `false`: [FocusRestorer]'s own effect independently sets
    // `memory.frozen = true` on the same transition.
    LaunchedEffect(isTop) {
        if (!isTop) refreshOwner.ownershipLost()
    }

    // Init owns first composition; subsequent host resumes cover playback/background returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onHostResume()
            // The refresh guard's `resumed` check only gates a new restore from starting; losing
            // RESUMED must cancel any in-flight refresh-restore job the same way losing `isTop`
            // does.
            if (event == Lifecycle.Event.ON_PAUSE) refreshOwner.ownershipLost()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Plain `remember`, not keyed on [isTop]: Home composes exactly once per session, so this must
    // never reset just because Home was hidden/revealed by navigation. `isLoading` is set false
    // exactly once, permanently, so a later refresh re-observing it is a no-op, never a re-trigger.
    // The effect tests its key, never the live state: a snapshot that lands before the first
    // effect runs must still reveal on the recomposition that carries the content, or the skeleton
    // retires before there is anything to show.
    var contentRevealed by remember { mutableStateOf(false) }
    val isLoading = chrome.isLoading
    LaunchedEffect(isLoading) {
        if (!isLoading) {
            HomeMarks.dataReady()
            contentRevealed = true
        }
    }
    val contentRevealAlpha by animateFloatAsState(
        targetValue = if (contentRevealed) 1f else 0f,
        animationSpec = tween(CONTENT_REVEAL_MS, easing = FastOutSlowInEasing),
        label = "homeContentReveal",
    )
    // The skeleton stays composed for the whole `isLoading` window plus the cross-fade, then leaves
    // composition so its `rememberInfiniteTransition` pulse stops ticking. A plain timer keyed on
    // the one-shot flip, not the animated alpha (which would recompose every animation frame).
    var showLoadingSkeleton by remember { mutableStateOf(true) }
    LaunchedEffect(contentRevealed) {
        if (contentRevealed) {
            delay(CONTENT_REVEAL_MS.toLong())
            showLoadingSkeleton = false
            HomeMarks.revealEnd()
        }
    }
    // Set by shelf 0's first cell (`onFirstCellPositioned` in [Shelf]) once actually
    // measured/placed.
    var shelf0FirstCellPositioned by remember { mutableStateOf(false) }
    val homeScrollState = rememberScrollState()
    // [HomeMasthead]'s measured wordmark+clock row height, so the still-pinned sync-status pill
    // can sit relative to it rather than a guessed constant.
    var mastheadRowHeight by remember { mutableStateOf(0.dp) }

    val continueWatchingTitle = stringResource(R.string.shelf_continue_watching)
    val nextUpTitle = stringResource(R.string.shelf_next_up)
    val favoritesTitle = stringResource(R.string.shelf_favorites)
    val latestInTemplate = stringResource(R.string.shelf_latest_in)
    val shelves = remember(state.shelves) {
        buildShelves(state.shelves, continueWatchingTitle, nextUpTitle, favoritesTitle) { viewName ->
            String.format(latestInTemplate, viewName)
        }
    }

    // docs/07 §1: progressive shelf mounting -- the hero plus shelves[0,1] always compose this
    // frame (shelf 0 carries the initial focus target), then [grownShelfCount] grows one shelf per
    // frame once [initialFocusPlaced], so the rest stop competing with the focus target's own image
    // requests. It only grows -- [mountedCount] clamps to [shelves]' current size without losing
    // that progress if the list briefly shrinks. [mountedShelfCount]/[initialMountCount] are pure
    // and unit-tested (ShelfAssembly.kt).
    var grownShelfCount by remember { mutableIntStateOf(0) }
    val mountedCount = mountedShelfCount(grownShelfCount, shelves.size)
    // One-shot: the moment initial focus lands (mirrors `home.focusReady`'s own condition below) --
    // the signal progressive mounting waits on.
    var initialFocusPlaced by remember { mutableStateOf(false) }
    val latestShelfCount by rememberUpdatedState(shelves.size)
    // Keyed on the shelf count too, so a refresh that adds a shelf resumes the trickle.
    LaunchedEffect(initialFocusPlaced, shelves.size) {
        if (!initialFocusPlaced) return@LaunchedEffect
        while (mountedShelfCount(grownShelfCount, latestShelfCount) < latestShelfCount) {
            withFrameNanos {}
            grownShelfCount++
        }
    }

    // docs/10-perf-logging.md `home.contentDrawn`: one-shot, the first pre-draw carrying real
    // shelf content -- registered only once [shelves] is non-empty, so it can't fire for the
    // loading skeleton, and self-removing so it never re-arms for a later refresh.
    val localView = LocalView.current
    // Plain holders, not snapshot state: a one-shot flag must not schedule a recomposition
    // inside the frames these marks measure.
    val contentDrawnMarked = remember { booleanArrayOf(false) }
    val shelvesMountedMarked = remember { booleanArrayOf(false) }
    DisposableEffect(shelves.isNotEmpty()) {
        if (!PerfLog.enabled || shelves.isEmpty() || contentDrawnMarked[0]) return@DisposableEffect onDispose {}
        contentDrawnMarked[0] = true
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                localView.viewTreeObserver.removeOnPreDrawListener(this)
                HomeMarks.contentDrawn()
                return true
            }
        }
        localView.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { localView.viewTreeObserver.removeOnPreDrawListener(listener) }
    }

    // `Modifier.clickable`'s `focusable()` asks its nearest scrollable ancestor to bring the
    // newly-focused node into view the moment it gains focus; if `requestFocus()` fires before
    // shelf 0's first cell (or the hero) is actually measured/placed, that computation reads a
    // transient/wrong position and animates the column to a wrong scroll target. Fix: [ready] stays
    // false -- so `FocusRestorer` places nothing yet -- until whichever of
    // [heroPositioned]/[shelf0FirstCellPositioned] this entry will seed has fired.
    val ready = if (shelves.isEmpty()) !chrome.isLoading else if (hero != null) heroPositioned else shelf0FirstCellPositioned

    // docs/15-focus-and-selection.md §5: scroll a not-yet-composed shelf cell into view by id
    // before `FocusRestorer`/`restoreNow` retries the lookup -- the shelf half of a
    // `"shelf:<id>/card:<id>"` key selects which [shelfListStates] entry to scroll; a hero key or a
    // gone shelf/card short-circuits to the fallback.
    memory.scrollTo = scrollTo@{ key ->
        val body = key.removePrefix("shelf:")
        if (body == key) return@scrollTo false
        val (shelfId, cardId) = body.split("/card:", limit = 2).let {
            if (it.size != 2) return@scrollTo false
            it[0] to it[1]
        }
        val targetShelfIndex = shelves.indexOfFirst { it.id == shelfId }
        if (targetShelfIndex < 0) return@scrollTo false
        if (targetShelfIndex >= mountedCount) {
            // The restore target is past progressive mounting's reach (docs/07 §1) -- mount
            // everything now so this shelf's LazyListState and cell keys exist to scroll/focus
            // into, then wait a few frames for it to actually compose.
            grownShelfCount = shelves.size
            var waited = 0
            while (shelfListStates[shelfId] == null && waited < 3) {
                withFrameNanos {}
                waited++
            }
        }
        val spec = shelves[targetShelfIndex]
        val index = spec.items.indexOfFirst { it.id == cardId }
        if (index < 0) return@scrollTo false
        val listState = shelfListStates[shelfId] ?: return@scrollTo false
        listState.scrollToItem(index)
        true
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = ready,
        fallback = homeFallback,
        tag = "home",
    )

    // docs/15 §3: "Content/library refresh -- kept if the key still resolves; otherwise rule 2,
    // then 3. Never index." A live refresh can drop the focused Continue Watching card from the
    // id-keyed LazyRow, clearing focus with nothing re-placing it. [shelves]/[hero] are this
    // screen's refresh signal. One `withFrameNanos` lets Compose apply the new list before this
    // reads [homeHasFocus]; only a refresh that left focus nowhere triggers a restore. Gated to
    // `isTop` so a background refresh under another screen leaves placement to [FocusRestorer]'s
    // becoming-top effect instead.
    //
    // `memory.seeded` guards the case where [shelves]/[hero] change identity while data is still
    // loading, before [ready] flips true -- nothing has focus yet because [ready] isn't met, not
    // because a refresh stole it. An unseeded run here would both fire the scroll-flash [ready]
    // prevents and swallow the real seed.
    //
    // [RefreshRestoreOwner] owns this guard's lifetime and freeze-reads: (A)
    // [refreshOwner.ownsFreeze] tells `eligible` below that a currently-true `memory.frozen`
    // belongs to a refresh job, not a drawer/navigation freeze, so cancellation doesn't
    // permanently block a replacement. (B) The guard's restore work must not outlive Home's focus
    // ownership or survive a transition mid-`frame`: [refreshOwner]'s epoch invalidates a waiting
    // trigger the instant `ownershipLost()` runs, and `eligible` reads [latestIsTop] live.
    val latestIsTop by rememberUpdatedState(isTop)
    LaunchedEffect(shelves, hero) {
        refreshOwner.onContentChanged(
            scope = coroutineScope,
            frame = { withFrameNanos {} },
            eligible = { ownsFreeze ->
                refreshGuardEligible(
                    isTop = latestIsTop,
                    seeded = memory.seeded,
                    resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED,
                    hasFocus = homeHasFocus,
                    frozen = memory.frozen,
                    ownsFreeze = ownsFreeze,
                )
            },
            restore = {
                memory.restoreNow(
                    focusGate,
                    fallback = { homeRefreshFallback(memory, shelves, shelfListStates, homeFallback) },
                    tag = "home-refresh",
                )
            },
        )
    }

    // Compose Foundation's default focus-into-view behavior only scrolls enough to reveal the
    // focused element -- it has no idea the wordmark/hero sit above shelf 0, so landing focus back
    // on shelf 0 doesn't necessarily scroll to the top. Wrapping the top region -- the hero, or
    // shelf 0 when there is no hero (docs/07 §1) -- in `onTopRegionFocusChanged` below gives an
    // explicit "focus re-entered the top" signal and an explicit smooth-scroll to 0, rather than
    // relying on the framework to infer it.
    //
    // Edge-triggered, top-region-only: firing on every FocusState emission relaunched a scroll-to-0
    // on every hero<->shelf-0 move that fought the newly-focused card's own bring-into-view. Only
    // the top region triggers it, and only on the false->true hasFocus edge, so moves within it
    // can't relaunch it.
    var topRegionHadFocus by remember { mutableStateOf(false) }
    // An organic D-pad move up from a shelf cell enters the hero at scroll=0, then the framework's
    // focus-triggered bring-into-view animates the column to scroll=82 for the Resume button,
    // clipping the masthead. The spec below is provided at the scrolling column: zero scroll
    // distance while focus is anywhere in the top region, default arithmetic otherwise, so shelf
    // cards keep their genuine bring-into-view and [onTopRegionFocusChanged] stays the top
    // region's only scroll authority.
    val topAwareBringIntoViewSpec = remember(homeScrollState) {
        @OptIn(ExperimentalFoundationApi::class)
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (topRegionHadFocus) 0f else super.calculateScrollDistance(offset, size, containerSize)
        }
    }
    val onTopRegionFocusChanged: (FocusState) -> Unit = remember(homeScrollState, coroutineScope, hero?.id) {
        { focusState ->
            val entered = focusState.hasFocus && !topRegionHadFocus
            val exited = !focusState.hasFocus && topRegionHadFocus
            topRegionHadFocus = focusState.hasFocus
            if (PerfLog.enabled) {
                Log.d(PerfLog.TAG, "focus top hasFocus=${focusState.hasFocus} entered=$entered frozen=${memory.frozen} scroll=${homeScrollState.value}")
            }
            // docs/18 preload: the hero region dwells like any other card once D-pad focus enters it.
            hero?.let { card ->
                if (entered) {
                    PreloadOnDwell.default.onCardFocused(card.id, card.itemType)
                } else if (exited) {
                    PreloadOnDwell.default.onCardUnfocused(card.id)
                }
            }
            // Each hero button's own `focusKey("hero:...")` already records itself as `lastKey` the
            // instant it gains focus, superseding whatever shelf-cell key was there before.
            if (entered && memory.frozen) {
                // `memory.frozen` is true for exactly the window a becoming-top/drawer-close
                // restore is placing focus programmatically; that edge must not scroll-reset. A
                // real D-pad move can never land here while frozen.
            } else if (entered && homeScrollState.value != 0) {
                coroutineScope.launch {
                    // One frame late on purpose: the framework's own focus-triggered
                    // bring-into-view animation starts in the same event and, sharing the default
                    // mutate priority, whichever starts last cancels the other -- ours must start
                    // second. If something still cancels the animated leg, retry once.
                    withFrameNanos { }
                    homeScrollState.animateScrollTo(0)
                    if (homeScrollState.value != 0) homeScrollState.animateScrollTo(0)
                }
            }
        }
    }

    val focusManager = LocalFocusManager.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte)
            // No `focusRestorer` here: a direct request bypasses the retained-layer enter gate, and
            // its saved-child restore failing after a hidden-retained period would stuff focus
            // into shelf 0 cell 0 just before the explicit focus-memory restore's own request,
            // which the focus system then silently drops. [FocusRestorer]'s becoming-top effect is
            // the only restore mechanism.
            .focusRequester(homeGroupFocusRequester)
            .focusGroup()
            // Tracks [homeHasFocus] for the refresh guard below; [onTopRegionFocusChanged] is a
            // separate, narrower listener scoped to the top region only.
            .onFocusChanged {
                homeHasFocus = it.hasFocus
                if (it.hasFocus && !chrome.isLoading) {
                    HomeMarks.focusReady()
                    initialFocusPlaced = true
                }
            }
            // docs/07 §1: while progressive mounting is still short of [shelves], a Down/Up we
            // perform ourselves so a miss (nothing composed yet that way) can mount everything and
            // retry, rather than the platform's default handling silently landing nowhere. Once
            // fully mounted this returns unhandled immediately -- default D-pad handling, unchanged.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionDown -> FocusDirection.Down
                    Key.DirectionUp -> FocusDirection.Up
                    else -> null
                } ?: return@onPreviewKeyEvent false
                if (mountedCount >= shelves.size) return@onPreviewKeyEvent false
                if (!focusManager.moveFocus(direction)) {
                    grownShelfCount = shelves.size
                    coroutineScope.launch {
                        // Same bounded wait as memory.scrollTo: the newly mounted shelves need
                        // a frame or two to have list states and focusable cells.
                        var waited = 0
                        do {
                            withFrameNanos {}
                            waited++
                        } while (shelfListStates.size < shelves.size && waited < 3)
                        focusManager.moveFocus(direction)
                    }
                }
                true
            },
    ) {
        val screenWidth = maxWidth
        val shelfWidthDp = (screenWidth - PAGE_MARGIN * 2).value
        val heroHeight = HERO_HEIGHT

        // Deliberately an eager scrolling Column, not a LazyColumn: a LazyColumn measured worse on
        // the reference hardware (scrolling back up recomposes each returning shelf synchronously
        // in the key-press frame). With ~10 shelves whose LazyRows only compose their visible
        // cards, the eager version's whole-tree cost is bounded; memory cost is absorbed by the
        // RGB_565 + capped-cache Coil setup. Re-measure if Home ever grows beyond one shelf per
        // library.
        // [topAwareBringIntoViewSpec] must wrap the scrollable itself -- verticalScroll reads it.
        CompositionLocalProvider(LocalBringIntoViewSpec provides topAwareBringIntoViewSpec) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(homeScrollState)
                // The hero starts flush at the top of the scroll content (no top padding);
                // [HomeMasthead] floats on the hero instead of pushing it down. Bottom clearance is
                // deliberately larger than a plain inter-shelf gap: the last shelf's own focus
                // ring/scale/shadow need room to clear the screen edge without clipping.
                .padding(bottom = BOTTOM_CONTENT_PADDING),
            verticalArrangement = Arrangement.spacedBy(SHELF_GAP),
        ) {
            // [HomeMasthead] below sits outside the `graphicsLayer`-alpha Column: it's visible
            // throughout [chrome.isLoading] and must never blink. Everything arriving with the
            // first snapshot (sync pill, hero, shelves) nests in the inner Column so one
            // `graphicsLayer` fades them in together; its `verticalArrangement` reproduces the
            // outer Column's [SHELF_GAP] so wrapping adds no spacing change.
            //
            // This Box is the one region both [HomeLoadingSkeleton] and the real content occupy --
            // a Box, not two Column siblings, so the two cross-fade in place instead of stacking
            // and doubling the scroll region while both are composed. [HomeLoadingSkeleton]'s alpha
            // is the exact inverse of [contentRevealAlpha].
            //
            // [HomeMasthead] is declared last among this Box's children so it draws on top of both
            // the skeleton and the real hero, and renders unconditionally since it must never
            // itself blink.
            Box(modifier = Modifier.fillMaxWidth()) {
                if (showLoadingSkeleton) {
                    HomeLoadingSkeleton(
                        heroHeight = heroHeight,
                        shelfWidthDp = shelfWidthDp,
                        modifier = Modifier.graphicsLayer { alpha = 1f - contentRevealAlpha },
                    )
                }
                Column(
                    modifier = Modifier
                        .graphicsLayer { alpha = contentRevealAlpha }
                        .padding(top = contentTopInset(hero)),
                    verticalArrangement = Arrangement.spacedBy(SHELF_GAP),
                ) {
                    if (shelves.isEmpty() && !chrome.isLoading) {
                        LaunchedEffect(Unit) { viewModel.loadServerHost() }
                        EmptyLibraryState(
                            host = chrome.serverHost,
                            libraries = chrome.views.size,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(initialFocusRequester)
                                .focusKey(memory, "home:empty")
                                .focusable(),
                        )
                    }
                    if (hero != null) {
                        // `onGloballyPositioned` sets [heroPositioned] once this region is actually
                        // measured/placed -- the becoming-top restore effect's hero/no-record
                        // branch waits on it before calling `requestFocus()`, same as
                        // [shelf0FirstCellPositioned].
                        Box(
                            modifier = Modifier
                                .onFocusChanged(onTopRegionFocusChanged)
                                .onGloballyPositioned { heroPositioned = true },
                        ) {
                            HeroBanner(
                                card = hero,
                                widthDp = screenWidth,
                                heightDp = heroHeight,
                                onOpenDetail = onOpenDetail,
                                memory = memory,
                                primaryFocusRequester = heroPrimaryFocusRequester,
                            )
                        }
                    }

                    shelves.forEachIndexed { index, spec ->
                        // docs/07 §1: progressive mounting -- a shelf past [mountedCount] doesn't
                        // compose yet, so it can never be a focus or scroll target (see
                        // [memory.scrollTo] above for the mount-all-and-wait a restore triggers).
                        if (index >= mountedCount) return@forEachIndexed
                        key(spec.id) {
                        // getOrPut against the screen-scoped map, not `remember`, keeps each
                        // shelf's horizontal scroll position stable against shelf-list reordering.
                        val listState = shelfListStates.getOrPut(spec.id) { LazyListState() }
                        if (index == 0) {
                            // Shelf 0 is the top region only without a hero; see [onTopRegionFocusChanged].
                            Box(modifier = if (hero == null) Modifier.onFocusChanged(onTopRegionFocusChanged) else Modifier) {
                                Shelf(
                                    spec = spec,
                                    shelfWidthDp = shelfWidthDp,
                                    listState = listState,
                                    memory = memory,
                                    initialFocusRequester = initialFocusRequester,
                                    onFirstCellPositioned = { shelf0FirstCellPositioned = true },
                                    onOpenDetail = onOpenDetail,
                                )
                            }
                        } else {
                            Shelf(
                                spec = spec,
                                shelfWidthDp = shelfWidthDp,
                                listState = listState,
                                memory = memory,
                                initialFocusRequester = null,
                                onFirstCellPositioned = null,
                                onOpenDetail = onOpenDetail,
                            )
                        }
                        }
                    }
                    // docs/10-perf-logging.md `home.shelvesMounted`: one-shot, fires once the LAST
                    // shelf mounts (docs/07 §1 progressive mounting can span several frames) -- a
                    // SideEffect placed right after the loop runs after Compose has applied it,
                    // matching that guarantee.
                    if (shelves.isNotEmpty() && mountedCount == shelves.size && !shelvesMountedMarked[0]) {
                        SideEffect {
                            shelvesMountedMarked[0] = true
                            PerfLog.markStartup("home.shelvesMounted")
                        }
                    }
                }

                // The floating masthead, declared last (draws on top), outside the alpha-gated
                // Column (never blinks).
                HomeMasthead(
                    showClock = chrome.showClock,
                    modifier = Modifier.align(Alignment.TopStart),
                    onRowHeightMeasured = { mastheadRowHeight = it },
                )
            }
        }
        }

        // A sibling of the scrolling Column, not nested in the [contentRevealAlpha]-gated inner
        // Column: it must stay visible during the loading skeleton. Pinned top-end, independent of
        // scroll position, never overlapping the hero's bottom-left title/buttons or the top-left
        // wordmark. Text comes from [tv.jellybeam.ui.home.common.HomeChrome.loadingStatusText] while loading, else the sync's
        // own progress line while a background sync is still running post-reveal; `null` once
        // neither applies.
        val syncStatusPillText = when {
            chrome.isLoading -> chrome.loadingStatusText
            chrome.isSyncing -> chrome.syncProgressText ?: "Syncing…"
            else -> null
        }
        // The clock lives in [HomeMasthead] (shares a row with the wordmark), so only the
        // sync-status pill is here, offset down by [mastheadRowHeight] plus a 6dp gap so it reads
        // as "below the clock" even though the two don't share a container.
        if (syncStatusPillText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = TOP_SAFE_AREA + mastheadRowHeight + 6.dp, end = PAGE_MARGIN),
            ) {
                HomeSyncStatusPill(text = syncStatusPillText)
            }
        }
    }
}

/**
 * docs/15 §3's rule-3 half for the refresh guard: [memory.restoreNow] already tried
 * [FocusMemory.lastKey] and missed, so this resolves the scrolled list's first-visible item before
 * falling through to [homeFallback].
 */
private fun homeRefreshFallback(
    memory: FocusMemory,
    shelves: List<ShelfSpec>,
    shelfListStates: Map<String, LazyListState>,
    homeFallback: () -> FocusTarget?,
): FocusTarget? {
    val shelfId = memory.lastKey
        ?.takeIf { it.startsWith("shelf:") }
        ?.removePrefix("shelf:")
        ?.substringBefore("/card:")
    val spec = shelfId?.let { id -> shelves.find { it.id == id } }
    val listState = shelfId?.let { shelfListStates[it] }
    if (spec != null && listState != null && spec.items.isNotEmpty()) {
        val index = listState.firstVisibleItemIndex.coerceIn(0, spec.items.lastIndex)
        val card = spec.items[index]
        memory.target("shelf:${spec.id}/card:${card.id}")?.let { return it }
    }
    return homeFallback()
}

/**
 * Replacement for the solid header band: the logo lockup floats directly over the hero on a top
 * gradient. A short top-down [MASTHEAD_GRADIENT_BRUSH] wash (shorter than
 * [tv.jellybeam.ui.theme.BackdropScrim]'s hero scrim) keeps [JellybeamHeaderLockup] legible over
 * bright art. Renders outside the [contentRevealAlpha]-gated Column (never blinks) and last among
 * its Box siblings.
 *
 * One Row, wordmark start / clock end via `SpaceBetween`, so the clock scrolls away with the
 * wordmark/hero as one unit instead of staying pinned alone. [onRowHeightMeasured] reports this
 * row's measured height back to [ClassicHomeScreen] so the sync-status pill can sit just below the clock.
 */
@Composable
private fun HomeMasthead(
    showClock: Boolean,
    modifier: Modifier = Modifier,
    onRowHeightMeasured: (Dp) -> Unit = {},
) {
    val density = LocalDensity.current
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MASTHEAD_HEIGHT)
                .background(MASTHEAD_GRADIENT_BRUSH),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TOP_SAFE_AREA, start = PAGE_MARGIN, end = PAGE_MARGIN)
                .onGloballyPositioned { coordinates ->
                    onRowHeightMeasured(with(density) { coordinates.size.height.toDp() })
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            JellybeamHeaderLockup()
            if (showClock) {
                HomeClock()
            }
        }
    }
}

/**
 * A hero-shaped block plus [SKELETON_SHELF_COUNT] + 1 shelf-shaped rows of pulsing tiles, filling
 * the region [ClassicHomeScreen]'s real hero+shelves content occupies once it arrives. Every size is
 * borrowed from the real layout it stands in for -- [heroHeight] is the exact value [ClassicHomeScreen]
 * computes for [HeroBanner], the first shelf's tiles use [CardFormatting.resumeRowHeightDp], and
 * the rest use [POSTER_CELL_WIDTH] -- so nothing visibly shifts when real content cross-fades in.
 *
 * Not focusable by construction: every shape is a plain [Box] with a background, so it can never
 * be reached by D-pad navigation or the becoming-top restore machinery.
 */
@Composable
private fun HomeLoadingSkeleton(heroHeight: Dp, shelfWidthDp: Float, modifier: Modifier = Modifier) {
    // One shared pulse drives every shape below (see [rememberSkeletonPulseAlpha]): a [State] read
    // only
    // inside each shape's `graphicsLayer{}` lambda, never in composition.
    val pulseAlpha = rememberSkeletonPulseAlpha()
    val resumeTileWidth = remember(shelfWidthDp) { CardFormatting.resumeCardWidthDp(shelfWidthDp, CELL_GAP.value).dp }
    val resumeTileHeight = remember(shelfWidthDp) { CardFormatting.resumeRowHeightDp(shelfWidthDp, CELL_GAP.value).dp }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SHELF_GAP)) {
        SkeletonBlock(
            pulseAlpha = pulseAlpha,
            cornerRadius = SKELETON_ART_RADIUS,
            modifier = Modifier.fillMaxWidth().height(heroHeight),
        )
        SkeletonShelf(pulseAlpha = pulseAlpha, tileWidth = resumeTileWidth, tileHeight = resumeTileHeight, shelfWidthDp = shelfWidthDp)
        repeat(SKELETON_SHELF_COUNT) {
            SkeletonShelf(pulseAlpha = pulseAlpha, tileWidth = POSTER_CELL_WIDTH, tileHeight = POSTER_CELL_WIDTH * 1.5f, shelfWidthDp = shelfWidthDp)
        }
    }
}

/**
 * One skeleton shelf: a title-shaped bar plus a row of [tileWidth] x [tileHeight] tiles spaced by
 * [CELL_GAP]. [tileCount] floor-divides to fit within [shelfWidthDp] so the row never overflows the
 * real
 * shelf's right edge -- this is a plain `Row`, not lazy, with no scroll container to clip it.
 */
@Composable
private fun SkeletonShelf(pulseAlpha: State<Float>, tileWidth: Dp, tileHeight: Dp, shelfWidthDp: Float) {
    val tileCount = remember(shelfWidthDp, tileWidth) {
        ((shelfWidthDp + CELL_GAP.value) / (tileWidth.value + CELL_GAP.value)).toInt().coerceAtLeast(1)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkeletonBlock(
            pulseAlpha = pulseAlpha,
            cornerRadius = SKELETON_TITLE_RADIUS,
            modifier = Modifier
                .padding(horizontal = PAGE_MARGIN)
                .width(SKELETON_TITLE_WIDTH)
                .height(SKELETON_TITLE_HEIGHT),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            modifier = Modifier.padding(horizontal = PAGE_MARGIN),
        ) {
            repeat(tileCount) {
                SkeletonBlock(pulseAlpha = pulseAlpha, cornerRadius = SKELETON_ART_RADIUS, modifier = Modifier.width(tileWidth).height(tileHeight))
            }
        }
    }
}

/** One pulsing skeleton shape (same recipe as [tv.jellybeam.ui.cards.PulsingFlatTile]) with a
 * configurable [clip] radius, covering both tile and title-bar shapes without two near-duplicate
 * composables.
 */
@Composable
private fun SkeletonBlock(pulseAlpha: State<Float>, cornerRadius: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .graphicsLayer { alpha = pulseAlpha.value }
            .clip(RoundedCornerShape(cornerRadius))
            .background(JellybeamTheme.SurfaceRaised),
    )
}

/**
 * docs/07 §1's hero banner: full-bleed backdrop ([CardFormatting.backdropArtSource], the same
 * chain Detail's header uses) with [BackdropScrim] so title/metadata/buttons read while the art's
 * right-hand third stays bright, a mono pistachio eyebrow, the item name, a metadata line
 * ([CardFormatting.heroMetaLine]), a resume progress bar + remaining-time label when resumable,
 * and two buttons: Resume/Play (launches [PlaybackActivity] directly) and More Info (delegates to
 * [onOpenDetail]). Buttons are plain focusable composables placed above shelf 0's LazyColumn item.
 *
 * The text column is top-aligned at [HERO_TEXT_TOP_OFFSET] from the hero's own top edge, now that
 * [HERO_HEIGHT] is fixed rather than a fraction of viewport height.
 */
@Composable
private fun HeroBanner(
    card: Card,
    widthDp: Dp,
    heightDp: Dp,
    onOpenDetail: (Card) -> Unit,
    /** docs/15-focus-and-selection.md §5 -- both buttons register under
     * `"hero:primary"`/`"hero:secondary"`.
     */
    memory: FocusMemory,
    /** Attached to the primary (Resume/Play) button -- §2 rule 3's declared fallback when there is
     * no restore key.
     */
    primaryFocusRequester: FocusRequester,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Computed inline rather than remembered so a refreshed Card with the same id recomputes
    // instead of
    // showing the first composition's stale value (docs/07-home-browse-behavior.md §1).
    val artSource = CardFormatting.backdropArtSource(card)
    val metaLine = CardFormatting.heroMetaLine(card)
    val isResumable = CardFormatting.heroIsResumable(card)
    val progress = CardFormatting.watchProgress(card)
    val timingLabel = CardFormatting.resumeTimingLabel(card)
    val isEpisode = card.itemType == "Episode"
    val seriesName = card.seriesName
    // "CONTINUE · <SERIES NAME>" for an episode, bare "CONTINUE" otherwise -- the hero is always
    // sourced from the core's [ClassicContent.hero].
    val eyebrowText = if (isEpisode && !seriesName.isNullOrBlank()) {
        "${CONTINUE_EYEBROW_PREFIX} · ${seriesName.uppercase()}"
    } else {
        CONTINUE_EYEBROW_PREFIX
    }
    val primaryLabel = stringResource(if (isResumable) R.string.hero_resume else R.string.hero_play)
    val moreInfoLabel = stringResource(R.string.hero_more_info)
    // Full-bleed at screen width, not a fixed 1280 -- bucketed so a window-size change doesn't
    // fragment
    // the server's image cache with one-off widths.
    val backdropWidth = remember(widthDp, density) {
        CardFormatting.bucketedImageWidth(with(density) { widthDp.roundToPx() })
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp),
    ) {
        // Keyed Crossfade: when the hero card changes (a return to Home after watching something
        // reorders
        // Continue Watching), the old backdrop fades into the new one instead of hard-swapping;
        // buttons/
        // text swap instantly. Keyed on art identity, not the whole Card, so a same-item progress
        // update
        // never re-fades.
        Crossfade(
            targetState = Pair(artSource, card.blurhash),
            animationSpec = tween(HERO_ART_CROSSFADE_MS),
            label = "heroArt",
        ) { (source, blurhash) ->
            CardArtImage(
                source = source,
                imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, backdropWidth) },
                itemName = card.name,
                contentAlpha = 1f,
                blurhash = blurhash,
                placeholderDimAlpha = BACKDROP_PLACEHOLDER_DIM,
                modifier = Modifier.fillMaxSize(),
                placeholderGraceMs = 0L,
            )
        }
        // Two-layer scrim: near-opaque under the (Start-aligned) text column, clear over the art's
        // right-hand third.
        BackdropScrim(textColumnSide = Alignment.Start)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(HERO_TEXT_COLUMN_WIDTH_FRACTION)
                .padding(start = HERO_INSET, top = HERO_TEXT_TOP_OFFSET, end = HERO_INSET),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Mono, pistachio, always shown -- the hero is always a Continue Watching card.
            BasicText(
                text = eyebrowText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = JellybeamTheme.MartianMono,
                    color = JellybeamTheme.Pistacchio,
                    fontSize = 10.sp,
                    letterSpacing = (-0.02).em,
                ),
            )
            BasicText(
                text = card.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    // Hero title is ExtraBold, not Bold.
                    fontWeight = FontWeight.ExtraBold,
                    color = JellybeamTheme.Panna,
                    fontSize = 34.sp,
                    lineHeight = 44.sp,
                ),
            )
            metaLine?.let {
                BasicText(
                    text = it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 14.sp),
                )
            }
            // Only when the hero actually has progress to show.
            if (isResumable && progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HERO_PROGRESS_LABEL_GAP)) {
                    Box(
                        modifier = Modifier
                            .width(HERO_PROGRESS_BAR_WIDTH)
                            .height(HERO_PROGRESS_BAR_HEIGHT)
                            .clip(RoundedCornerShape(50))
                            .background(JellybeamTheme.Hairline),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(JellybeamTheme.Pistacchio),
                        )
                    }
                    timingLabel?.let {
                        BasicText(
                            text = it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                fontFamily = JellybeamTheme.MartianMono,
                                color = JellybeamTheme.Panna2,
                                fontSize = 11.sp,
                                letterSpacing = (-0.02).em,
                            ),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HERO_BUTTON_GAP)) {
                HeroButton(
                    label = primaryLabel,
                    isPrimary = true,
                    onClick = { context.startActivity(PlaybackActivity.intent(context, card.id)) },
                    // docs/15-focus-and-selection.md §5: the key doubles as §2 rule 3's fallback
                    // via the
                    // plain [primaryFocusRequester] -- FocusRestorer needs a direct requester since
                    // a fresh
                    // entry has no key to resolve.
                    modifier = Modifier
                        .focusKey(memory, "hero:primary")
                        .focusRequester(primaryFocusRequester),
                )
                HeroButton(
                    label = moreInfoLabel,
                    isPrimary = false,
                    onClick = { onOpenDetail(card) },
                    modifier = Modifier.focusKey(memory, "hero:secondary"),
                )
            }
        }
    }
}

/** [HeroBanner]'s eyebrow prefix -- the hero shelf is always Continue Watching's first card. */
private const val CONTINUE_EYEBROW_PREFIX = "CONTINUE"

/**
 * One hero button. `isPrimary` (Resume/Play) is pistachio-filled, swapping to [JellybeamTheme.Sheen]
 * on focus; the secondary (More Info) button is a translucent [JellybeamTheme.Notte] wash with a
 * [JellybeamTheme.HairlineStrong] border. Both are fully rounded pills.
 *
 * Ring color override: a same-hue [JellybeamTheme.Pistacchio] ring would nearly disappear against
 * the pistachio-filled primary button's fill, so [focusRing] draws it in [JellybeamTheme.Sheen]
 * there instead -- same recipe [tv.jellybeam.ui.cards.ArtBox] uses for card art.
 */
@Composable
private fun HeroButton(label: String, isPrimary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocusedState = interactionSource.collectIsFocusedAsState()
    val showFocusStyle by remember(interactionSource) { derivedStateOf { isFocusedState.value } }

    val background = if (isPrimary) {
        if (showFocusStyle) JellybeamTheme.Sheen else JellybeamTheme.Pistacchio
    } else {
        JellybeamTheme.Notte.copy(alpha = 0.72f)
    }
    val textColor = if (isPrimary) JellybeamTheme.Notte else JellybeamTheme.Panna
    val ringColor = if (isPrimary) JellybeamTheme.Sheen else JellybeamTheme.Pistacchio
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .background(background, shape)
            .then(
                if (!isPrimary) {
                    Modifier.border(1.dp, JellybeamTheme.HairlineStrong, shape)
                } else {
                    Modifier
                },
            )
            .focusRing(showFocusStyle, cornerRadius = 999.dp, color = ringColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                // Buttons are Bold, not Medium.
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontSize = 15.sp,
            ),
        )
    }
}

// OptIn: LocalBringIntoViewSpec/BringIntoViewSpec ([rememberShelfBringIntoViewSpec], the per-shelf
// horizontal spec around this function's LazyRow) are still experimental foundation API in 1.10,
// same as
// [ClassicHomeScreen]'s vertical `topAwareBringIntoViewSpec`.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Shelf(
    spec: ShelfSpec,
    shelfWidthDp: Float,
    listState: LazyListState,
    /** docs/15 §5: each cell registers `"shelf:<spec.id>/card:<card.id>"` -- HomeScreen's
     * becoming-top/drawer-close restore resolves and scrolls to it directly, no per-cell restore
     * requester needed.
     */
    memory: FocusMemory,
    initialFocusRequester: FocusRequester?,
    onFirstCellPositioned: (() -> Unit)?,
    onOpenDetail: (Card) -> Unit,
) {
    var focusedIndex by remember(spec.title) { mutableStateOf<Int?>(null) }
    // CELL_GAP passed explicitly so this can never silently drift from the gap the LazyRow below
    // actually
    // lays cards out with.
    val rowHeight = remember(shelfWidthDp) { CardFormatting.resumeRowHeightDp(shelfWidthDp, CELL_GAP.value).dp }
    val artHeight = when (spec.kind) {
        ShelfKind.RESUME -> rowHeight
        ShelfKind.POSTER -> POSTER_CELL_WIDTH * 1.5f
    }
    // Bucketed to the cell's actual drawn width (docs/07 §1) so Home shares a rendition with the
    // library grid and Detail's placeholder instead of fetching a flat, cache-fragmenting size.
    val density = LocalDensity.current
    val posterImageWidth = remember(density) {
        CardFormatting.bucketedImageWidth(with(density) { POSTER_CELL_WIDTH.roundToPx() })
    }
    val resumeImageWidth = remember(shelfWidthDp, density) {
        val widthDp = CardFormatting.resumeCardWidthDp(shelfWidthDp, CELL_GAP.value)
        CardFormatting.bucketedImageWidth(with(density) { widthDp.dp.roundToPx() })
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Row header, with an optional item count in mono beside it.
        Row(
            modifier = Modifier.padding(horizontal = PAGE_MARGIN),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                text = spec.title,
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    fontWeight = FontWeight.Bold,
                    color = JellybeamTheme.Panna,
                    fontSize = 17.sp,
                ),
            )
            if (spec.items.isNotEmpty()) {
                BasicText(
                    text = spec.items.size.toString(),
                    style = TextStyle(
                        fontFamily = JellybeamTheme.MartianMono,
                        color = JellybeamTheme.Grigio,
                        fontSize = 10.sp,
                        letterSpacing = (-0.02).em,
                    ),
                )
            }
        }
        // leftFadeHeight covers the whole card (art + title text), not just artHeight -- see
        // [CARD_TEXT_BLOCK_HEIGHT].
        EdgeFadedRow(listState = listState, artHeight = artHeight, leftFadeHeight = artHeight + CARD_TEXT_BLOCK_HEIGHT) {
            // An inner provider around this shelf's LazyRow, pinning its focused item at
            // [PAGE_MARGIN] --
            // the same margin `contentPadding` below lays out item 0 against while unscrolled. A
            // provider
            // only reaches composition below where it's declared, so this cannot touch the outer
            // column's
            // own vertical spec (HomeScreen's `topAwareBringIntoViewSpec`).
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
            ) {
                itemsIndexed(spec.items, key = { _, card -> card.id }, contentType = { _, _ -> spec.kind }) { index, card ->
                    // docs/07 §4: isFocused reads through derivedStateOf, keyed per-index, rather
                    // than
                    // straight off the shared `focusedIndex` state -- otherwise one D-pad press
                    // would
                    // invalidate the whole row instead of just the two cells whose boolean flips.
                    // The ring
                    // shows from the moment initial focus lands, not gated on a prior D-pad press.
                    val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }

                    // docs/15-focus-and-selection.md §5: [focusKey] first in the chain -- it must
                    // sit above
                    // the modifier that actually creates the focus target (see
                    // [tv.jellybeam.ui.focus.focusKey]
                    // for why order matters). `animateItem` second: without it, a post-playback
                    // refresh
                    // reordering Continue Watching teleports the row to the new order in one frame
                    // instead
                    // of sliding/fading.
                    var cellModifier = Modifier
                        .focusKey(memory, "shelf:${spec.id}/card:${card.id}")
                        .animateItem()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                focusedIndex = index
                                PreloadOnDwell.default.onCardFocused(card.id, card.itemType)
                            } else {
                                if (focusedIndex == index) {
                                    focusedIndex = null
                                }
                                PreloadOnDwell.default.onCardUnfocused(card.id)
                            }
                        }
                    if (index == 0 && initialFocusRequester != null) {
                        // §2 rule 3's declared fallback (shelf 0 cell 0, when there's no hero): a
                        // plain requester since FocusRestorer needs a direct target for a fresh
                        // entry.
                        cellModifier = cellModifier.focusRequester(initialFocusRequester)
                    }
                    if (index == 0 && onFirstCellPositioned != null) {
                        // The readiness signal gating when it's safe to call `requestFocus()`
                        // without
                        // racing Compose's own focus-triggered bring-into-view scroll.
                        cellModifier = cellModifier.onGloballyPositioned { onFirstCellPositioned() }
                    }

                    when (spec.kind) {
                        ShelfKind.POSTER -> PosterCard(
                            card = card,
                            isFocused = isFocused,
                            imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, posterImageWidth) },
                            onClick = { onOpenDetail(card) },
                            modifier = cellModifier,
                            width = POSTER_CELL_WIDTH,
                        )

                        ShelfKind.RESUME -> {
                            // Uniform 16:9 (see CardFormatting.resumeArtSource): every resume card,
                            // movie
                            // or episode, requests the same width.
                            ResumeCard(
                                card = card,
                                isFocused = isFocused,
                                rowHeight = rowHeight,
                                imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, resumeImageWidth) },
                                onClick = { onOpenDetail(card) },
                                modifier = cellModifier,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * docs/07 §4: gradient overlays at a shelf's scroll edges -- left only once scrolled past the
 * start,
 * right only while more content remains ([LazyListState.canScrollForward]). Wraps [content] in a
 * `Box`
 * so the fades layer on top without affecting layout/scrolling; neither fade `Box` carries a
 * `clickable`/`focusable`/pointer-input modifier, only `background`, so neither can intercept a
 * D-pad
 * move or click.
 *
 * The trailing/right fade is [EDGE_FADE_WIDTH] wide and [artHeight] tall, covering only the art
 * strip a
 * card's focus ring sits against. The leading/left fade is [leftFadeHeight] tall (a scrolled-off
 * card's
 * full height -- see [CARD_TEXT_BLOCK_HEIGHT]) and [LEFT_GUTTER_FADE_WIDTH] wide, not
 * [EDGE_FADE_WIDTH] --
 * see that constant for why the width must match the safe-area gutter.
 */
@Composable
private fun EdgeFadedRow(listState: LazyListState, artHeight: Dp, leftFadeHeight: Dp, content: @Composable () -> Unit) {
    val showLeftFade by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val showRightFade by remember { derivedStateOf { listState.canScrollForward } }

    Box(modifier = Modifier.fillMaxWidth()) {
        content()
        if (showLeftFade) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(LEFT_GUTTER_FADE_WIDTH)
                    .height(leftFadeHeight)
                    .background(EDGE_FADE_LEFT_BRUSH),
            )
        }
        if (showRightFade) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(EDGE_FADE_WIDTH)
                    .height(artHeight)
                    .background(EDGE_FADE_RIGHT_BRUSH),
            )
        }
    }
}
