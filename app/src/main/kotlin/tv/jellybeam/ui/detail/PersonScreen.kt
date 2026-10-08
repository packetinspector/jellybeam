package tv.jellybeam.ui.detail

import androidx.compose.foundation.lazy.LazyListScope
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.POSTER_CELL_WIDTH
import tv.jellybeam.ui.cards.PosterCard
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.cards.rememberHeaderPageBringIntoViewSpec
import tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec
import tv.jellybeam.ui.discover.DiscoverErrorMessage
import tv.jellybeam.ui.discover.DiscoverPosterSkeletonRow
import tv.jellybeam.ui.discover.SeerrPosterCard
import tv.jellybeam.ui.discover.seerrCardKey
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.FocusTarget
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.rememberFocusMemory
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind
import uniffi.jellybeam_core.PersonPage
import uniffi.jellybeam_core.SeerrMediaType

private val PORTRAIT_WIDTH = 100.dp
private val PORTRAIT_HEIGHT = 150.dp
private val ROW_GAP = 16.dp
private val ROW_FADE_HEIGHT = POSTER_CELL_WIDTH * 1.5f + 40.dp
private const val LIBRARY_KEY_PREFIX = "item:"
private const val DISCOVER_KEY_PREFIX = "card:"

/**
 * The library person page (docs/11 §Person page): header, "In your library", and, when Seerr
 * answers, "Not in your library". Focus restore per docs/15 §2-§5: poster keys `item:<id>` and
 * `card:<seerrCardKey>`; fresh entry lands on the first library poster, else the first Seerr card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonScreen(
    personId: String,
    personName: String,
    onOpenDetail: (Card) -> Unit,
    onOpenDiscoverDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry (see [tv.jellybeam.ui.library.LibraryScreen]). */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: PersonViewModel = viewModel(factory = PersonViewModelFactory(AppGraph.gateway, AppGraph.strings, personId)),
) {
    val state by viewModel.state.collectAsState()
    val page = state.page
    val strings = rememberUiStrings()
    val scope = rememberCoroutineScope()
    val memory = rememberFocusMemory()
    val libraryListState = remember { LazyListState() }
    val discoverListState = remember { LazyListState() }
    val libraryFirst = remember { FocusRequester() }
    val discoverFirst = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val headerFocus = remember { FocusRequester() }
    val posterWidthPx = rememberPosterWidthPx()
    var synopsisOpen by remember { mutableStateOf(false) }
    // docs/15 §2: the header (portrait, name, biography) has no focus stop of its own once a poster
    // row exists, so the first row counts as its end: focusing it while scrolled brings the page
    // back to the top instead of leaving the name clipped above the row.
    val scrollState = rememberScrollState()
    var headerBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    val firstRowModifier = Modifier.onGloballyPositioned { headerBottomPx = it.boundsInRoot().bottom + scrollState.value }

    memory.scrollTo = { key ->
        when {
            key.startsWith(LIBRARY_KEY_PREFIX) -> {
                val index = page?.library?.indexOfFirst { it.id == key.removePrefix(LIBRARY_KEY_PREFIX) } ?: -1
                if (index >= 0) libraryListState.scrollToItem(index)
                index >= 0
            }
            key.startsWith(DISCOVER_KEY_PREFIX) -> {
                val cardKey = key.removePrefix(DISCOVER_KEY_PREFIX)
                val index = state.discover.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == cardKey }
                if (index >= 0) discoverListState.scrollToItem(index)
                index >= 0
            }
            else -> false
        }
    }

    // A title opened from here may come back played: its badge is re-read on return, and mirror
    // changes keep it current from then on (the scheduler samples faster while top).
    var leftTop by remember { mutableStateOf(false) }
    LaunchedEffect(isTop) {
        viewModel.setActive(isTop)
        if (!isTop) {
            leftTop = true
        } else if (leftTop) {
            leftTop = false
            viewModel.refreshLibrary()
        }
    }

    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        // An error lands focus on Retry instead of leaving nothing focused.
        ready = state.error != null || PersonPageLogic.focusReady(page != null, page?.library?.size ?: 0, state.discoverPending),
        fallback = {
            if (state.error != null) return@FocusRestorer retryFocus.asFocusTarget()
            when (PersonPageLogic.initialRow(page?.library?.size ?: 0, state.discover.size, hasMore = memory.hasKey(OVERVIEW_MORE_KEY))) {
                PersonPageLogic.InitialRow.LIBRARY -> libraryFirst.asFocusTarget()
                PersonPageLogic.InitialRow.DISCOVER -> discoverFirst.asFocusTarget()
                // MORE's stop composes a frame after the text layout that clamps it, so the target
                // resolves per attempt instead of once here.
                PersonPageLogic.InitialRow.MORE, PersonPageLogic.InitialRow.HEADER ->
                    emptyPageTarget(memory, hasOverview = !page?.overview.isNullOrBlank(), header = headerFocus.asFocusTarget())
            }
        },
        tag = "person",
        reloadKey = state.loadGeneration,
    )

    Box(modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberHeaderPageBringIntoViewSpec(scrollState) { headerBottomPx }) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            when {
                state.isLoading -> {
                    PersonName(personName)
                    DiscoverPosterSkeletonRow(count = 8)
                }
                state.error != null -> DiscoverErrorMessage(
                    state.error.orEmpty(),
                    onRetry = viewModel::retry,
                    retryFocusRequester = retryFocus,
                )
                page != null -> {
                    PersonHeader(
                        page = page,
                        lifeLines = PersonPageLogic.lifeLines(strings, page.birthDate, page.deathDate, page.birthPlace),
                        memory = memory,
                        onMore = { synopsisOpen = true },
                        headerFocus = headerFocus.takeIf {
                            PersonPageLogic.headerFocusable(true, page.library.size, state.discover.size, state.discoverPending)
                        },
                    )
                    if (page.library.isNotEmpty()) {
                        PosterShelf(titleRes = R.string.person_in_library, listState = libraryListState, modifier = firstRowModifier) {
                            itemsIndexed(page.library, key = { _, card -> card.id }) { index, card ->
                                var isFocused by remember(card.id) { mutableStateOf(false) }
                                PosterCard(
                                    card = card,
                                    isFocused = isFocused,
                                    imageUrl = { itemId, kind, tag -> AppGraph.gateway.imageUrl(itemId, kind, tag, posterWidthPx) },
                                    onClick = { onOpenDetail(card) },
                                    modifier = Modifier
                                        .focusKey(memory, "$LIBRARY_KEY_PREFIX${card.id}")
                                        .then(if (index == 0) Modifier.focusRequester(libraryFirst) else Modifier)
                                        .onFocusChanged { isFocused = it.isFocused },
                                )
                            }
                        }
                    } else {
                        BasicText(
                            text = stringResource(R.string.person_nothing_in_library),
                            modifier = Modifier.padding(horizontal = PAGE_MARGIN),
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp),
                        )
                    }
                    if (state.discover.isNotEmpty()) {
                        PosterShelf(
                            titleRes = R.string.person_not_in_library,
                            listState = discoverListState,
                            modifier = if (page.library.isEmpty()) firstRowModifier else Modifier,
                        ) {
                            itemsIndexed(state.discover, key = { _, card -> seerrCardKey(card.mediaType, card.tmdbId) }) { index, card ->
                                var isFocused by remember(card.tmdbId) { mutableStateOf(false) }
                                SeerrPosterCard(
                                    posterUrl = card.posterUrl,
                                    title = card.title,
                                    year = card.year,
                                    availability = card.availability,
                                    isFocused = isFocused,
                                    onClick = { onOpenDiscoverDetail(card.mediaType, card.tmdbId) },
                                    modifier = Modifier
                                        .focusKey(memory, "$DISCOVER_KEY_PREFIX${seerrCardKey(card.mediaType, card.tmdbId)}")
                                        .then(if (index == 0) Modifier.focusRequester(discoverFirst) else Modifier)
                                        .onFocusChanged { isFocused = it.isFocused },
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (synopsisOpen && page?.overview != null) {
            SynopsisPanel(
                title = page.name,
                overview = page.overview.orEmpty(),
                open = synopsisOpen,
                onClose = {
                    synopsisOpen = false
                    scope.launch { memory.placeByKeys(listOf(OVERVIEW_MORE_KEY), focusGate) }
                },
                isTop = isTop,
                focusGate = focusGate,
            )
        }
    }
}

/** An empty page's landing: MORE once registered, else the name (see [PersonPageLogic.emptyPageAnchor]). */
private fun emptyPageTarget(memory: FocusMemory, hasOverview: Boolean, header: FocusTarget): FocusTarget = object : FocusTarget {
    private var misses = 0

    override fun requestFocus(): Boolean {
        val more = memory.target(OVERVIEW_MORE_KEY)
        return when (PersonPageLogic.emptyPageAnchor(more != null, hasOverview, misses)) {
            PersonPageLogic.EmptyPageAnchor.MORE -> more?.requestFocus() ?: false
            PersonPageLogic.EmptyPageAnchor.WAIT -> false.also { misses++ }
            PersonPageLogic.EmptyPageAnchor.HEADER -> header.requestFocus()
        }
    }
}

@Composable
private fun rememberPosterWidthPx(): UInt {
    val density = LocalDensity.current
    return remember(density) { CardFormatting.bucketedImageWidth(with(density) { POSTER_CELL_WIDTH.roundToPx() }) }
}

@Composable
private fun PersonName(name: String) {
    BasicText(
        text = name,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = PAGE_MARGIN),
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 28.sp),
    )
}

@Composable
private fun PersonHeader(
    page: PersonPage,
    lifeLines: List<String>,
    memory: FocusMemory,
    onMore: () -> Unit,
    /** Non-null only on a page with no poster row: the name is then the D-pad's anchor. */
    headerFocus: FocusRequester?,
) {
    val density = LocalDensity.current
    val portraitWidthPx = remember(density) { CardFormatting.bucketedImageWidth(with(density) { PORTRAIT_WIDTH.roundToPx() }) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .background(JellybeamTheme.SurfaceRaised),
        ) {
            page.primaryImageTag?.let { tag ->
                CardArtImage(
                    source = ArtSource.Own(page.id, tag, ImageKind.PRIMARY),
                    imageUrl = { itemId, kind, imageTag -> AppGraph.gateway.imageUrl(itemId, kind, imageTag, portraitWidthPx) },
                    itemName = page.name,
                    contentAlpha = 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val nameSource = remember { MutableInteractionSource() }
            val nameFocused by nameSource.collectIsFocusedAsState()
            BasicText(
                text = page.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (headerFocus != null) {
                    Modifier.focusRequester(headerFocus).focusRing(isFocused = nameFocused, cornerRadius = 4.dp).focusable(interactionSource = nameSource)
                } else {
                    Modifier
                },
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 28.sp),
            )
            lifeLines.forEach { line ->
                BasicText(
                    text = line,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 12.sp),
                )
            }
            OverviewBlock(text = page.overview, color = JellybeamTheme.Panna2, memory = memory, moreKey = OVERVIEW_MORE_KEY, onMore = onMore)
        }
    }
}

/** A titled, edge-faded poster row under the shelf scroll rule (docs/15 §2). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterShelf(
    @StringRes titleRes: Int,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(titleRes)
        EdgeFadedLazyRow(listState = listState, height = ROW_FADE_HEIGHT) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
                    contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
                    content = content,
                )
            }
        }
    }
}
