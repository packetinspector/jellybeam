package tv.jellybeam.ui.discover

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.cards.PlaceholderTile
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.cards.rememberHeaderPageBringIntoViewSpec
import tv.jellybeam.ui.cards.rememberShelfBringIntoViewSpec
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.asFocusTarget
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import uniffi.jellybeam_core.SeerrAvailability
import uniffi.jellybeam_core.SeerrCard
import uniffi.jellybeam_core.SeerrMediaType
import uniffi.jellybeam_core.SeerrPersonRef
import uniffi.jellybeam_core.SeerrProfile
import uniffi.jellybeam_core.SeerrRootFolder
import uniffi.jellybeam_core.SeerrSeasonStatus
import uniffi.jellybeam_core.SeerrServiceServer

private val PAGE_MARGIN = 32.dp

/** Focus-key prefixes for this screen's shelves -- baked into the key itself
 * (`"shelf:<group>/card:<seerrCardKey>"`) so Similar and Recommended, which can share a title,
 * never collide in one [tv.jellybeam.ui.focus.FocusMemory]. */
private const val GROUP_SIMILAR = "similar"
private const val GROUP_RECOMMENDED = "recommended"

/** Stable per-action ids, keyed `"action:<id>"` in [tv.jellybeam.ui.focus.FocusMemory]; independent
 * of display order so a remembered action is still found if row composition order changes. */
private const val ACTION_GO_TO_LIBRARY = "go-to-library"
private const val ACTION_CANCEL_REQUEST = "cancel-request"
private const val ACTION_REQUEST = "request"
private const val ACTION_REQUEST_4K = "request-4k"
private const val ACTION_TRAILER = "trailer"

/** [SeerrAvailability]'s detail-line text (docs/14's availability-line rule), one string per
 * state. */
@Composable
private fun SeerrAvailability.detailLabel(): String = when (this) {
    SeerrAvailability.NOT_REQUESTED -> stringResource(R.string.discover_availability_not_requested)
    SeerrAvailability.PENDING -> stringResource(R.string.discover_availability_pending)
    SeerrAvailability.PROCESSING -> stringResource(R.string.discover_availability_processing)
    SeerrAvailability.PARTIALLY_AVAILABLE -> stringResource(R.string.discover_availability_partially_available)
    SeerrAvailability.AVAILABLE -> stringResource(R.string.discover_availability_available)
}

/** One action-row pill's identity + behavior, built once per composition so the row and the
 * becoming-top restore effect agree on the same [id]/order. */
private data class ActionSpec(
    val id: String,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val primary: Boolean = false,
)

/**
 * A Seerr title's detail page (docs/14-seerr-discover.md UI section): backdrop, title/meta/scores,
 * overview, availability line, action row, TV season list + request dialog, and
 * Cast/Similar/Recommended shelves.
 *
 * docs/15-focus-and-selection.md §2-§5: [tv.jellybeam.ui.focus.FocusMemory] keys every focusable
 * directly (`"action:<id>"`, `"person:<id>"`, `"shelf:similar/card:<key>"`,
 * `"shelf:recommended/card:<key>"`, group baked in since Similar/Recommended can share a title).
 * No §2 rule-2 selected item, so `selectedKey` stays `null`. [FocusRestorer]'s `ready` gates on
 * `!state.isLoading && state.card != null`.
 *
 * §4: [RequestOptionsDialog]/[ConfirmDialog] are overlay `Box`es in the same retained layer, not
 * a Compose `Dialog`, so opening one doesn't flip `isTop`; the invoker is captured via
 * [FocusMemory.captureInvoker] before either opens and restored via [FocusMemory.placeByKeys] on
 * close.
 */
@Composable
fun DiscoverDetailScreen(
    mediaType: SeerrMediaType,
    tmdbId: Long,
    onOpenDetail: (mediaType: SeerrMediaType, tmdbId: Long) -> Unit,
    onOpenPerson: (personId: Long) -> Unit,
    /** [tv.jellybeam.MainActivity]'s `openDiscoverLibraryItem` -- resolves and pushes the native
     * Jellyfin Detail page; fails open if resolution fails. */
    onGoToLibrary: (itemId: String) -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- see [tv.jellybeam.ui.library.LibraryScreen]'s own
     * param doc. */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    viewModel: DiscoverDetailViewModel = viewModel(
        factory = DiscoverDetailViewModelFactory(AppGraph.gateway, mediaType, tmdbId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val firstActionRequester = remember { FocusRequester() }

    val memory = rememberFocusMemory(mediaType, tmdbId)
    val castListState = remember { LazyListState() }
    val similarListState = remember { LazyListState() }
    val recommendedListState = remember { LazyListState() }

    // §5: scroll a not-yet-composed cast/shelf member into view by key before FocusRestorer
    // retries. Action pills are never virtualized, so a miss on "action:" is genuine.
    memory.scrollTo = scrollTo@{ key ->
        when {
            key.startsWith("person:") -> {
                val id = key.removePrefix("person:")
                val index = state.cast.indexOfFirst { it.personId.toString() == id }
                if (index < 0) return@scrollTo false
                castListState.scrollToItem(index)
                true
            }
            key.startsWith("shelf:$GROUP_SIMILAR/card:") -> {
                val cardKey = key.removePrefix("shelf:$GROUP_SIMILAR/card:")
                val index = state.similar.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == cardKey }
                if (index < 0) return@scrollTo false
                similarListState.scrollToItem(index)
                true
            }
            key.startsWith("shelf:$GROUP_RECOMMENDED/card:") -> {
                val cardKey = key.removePrefix("shelf:$GROUP_RECOMMENDED/card:")
                val index = state.recommendations.indexOfFirst { seerrCardKey(it.mediaType, it.tmdbId) == cardKey }
                if (index < 0) return@scrollTo false
                recommendedListState.scrollToItem(index)
                true
            }
            else -> false
        }
    }

    // §2 rule 3: a stale key whose cast/shelf group still has members restores to that group's
    // nearest visible member; an empty/gone group or fresh entry falls to the first action pill.
    FocusRestorer(
        memory = memory,
        isTop = isTop,
        focusGate = focusGate,
        ready = !state.isLoading && state.card != null,
        fallback = {
            val staleKey = memory.lastKey
            val nearby = when {
                staleKey?.startsWith("person:") == true && state.cast.isNotEmpty() -> {
                    val index = castListState.firstVisibleItemIndex.coerceIn(0, state.cast.lastIndex)
                    memory.target("person:${state.cast[index].personId}")
                }
                staleKey?.startsWith("shelf:$GROUP_SIMILAR/card:") == true && state.similar.isNotEmpty() -> {
                    val index = similarListState.firstVisibleItemIndex.coerceIn(0, state.similar.lastIndex)
                    val card = state.similar[index]
                    memory.target("shelf:$GROUP_SIMILAR/card:${seerrCardKey(card.mediaType, card.tmdbId)}")
                }
                staleKey?.startsWith("shelf:$GROUP_RECOMMENDED/card:") == true && state.recommendations.isNotEmpty() -> {
                    val index = recommendedListState.firstVisibleItemIndex.coerceIn(0, state.recommendations.lastIndex)
                    val card = state.recommendations[index]
                    memory.target("shelf:$GROUP_RECOMMENDED/card:${seerrCardKey(card.mediaType, card.tmdbId)}")
                }
                else -> null
            }
            nearby ?: firstActionRequester.asFocusTarget()
        },
        tag = "discover-detail",
    )

    // §4: places the captured invoker back once the dialog closes, then clears it.
    val dialogOpen = state.requestDialogVisible || state.cancelDialogVisible
    var wasDialogOpen by remember { mutableStateOf(dialogOpen) }
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen && wasDialogOpen) {
            memory.placeByKeys(listOfNotNull(memory.invokerKey), focusGate)
            memory.invokerKey = null
        }
        wasDialogOpen = dialogOpen
    }

    LaunchedEffect(state.transientEvent) {
        val event = state.transientEvent ?: return@LaunchedEffect
        val messageRes = when (event) {
            DiscoverDetailTransientEvent.REQUEST_SUBMITTED -> R.string.discover_request_submitted
            DiscoverDetailTransientEvent.REQUEST_CANCELLED -> R.string.discover_request_cancelled
        }
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
        viewModel.dismissTransientEvent()
    }

    Box(modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        when {
            state.isLoading -> Unit // blank Notte, same "between-screens" convention as SettingsScreen
            state.notConfigured -> DiscoverMessage(stringResource(R.string.discover_not_configured))
            state.card == null && state.error != null -> DiscoverErrorMessage(state.error!!, onRetry = viewModel::retry)
            state.card != null -> DiscoverDetailContent(
                state = state,
                viewModel = viewModel,
                onOpenDetail = onOpenDetail,
                onOpenPerson = onOpenPerson,
                onGoToLibrary = onGoToLibrary,
                firstActionRequester = firstActionRequester,
                memory = memory,
                castListState = castListState,
                similarListState = similarListState,
                recommendedListState = recommendedListState,
            )
        }

        if (state.requestDialogVisible) {
            RequestOptionsDialog(state = state, viewModel = viewModel, focusGate = focusGate)
        }
        if (state.cancelDialogVisible) {
            ConfirmDialog(
                focusGate = focusGate,
                title = stringResource(R.string.discover_cancel_request_title),
                body = stringResource(R.string.discover_cancel_request_body),
                confirmLabel = stringResource(if (state.isCancelling) R.string.discover_cancelling else R.string.discover_cancel_request_confirm),
                dismissLabel = stringResource(R.string.discover_cancel_request_dismiss),
                busy = state.isCancelling,
                onConfirm = viewModel::confirmCancel,
                onDismiss = viewModel::dismissCancelDialog,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DiscoverDetailContent(
    state: DiscoverDetailUiState,
    viewModel: DiscoverDetailViewModel,
    onOpenDetail: (SeerrMediaType, Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onGoToLibrary: (String) -> Unit,
    firstActionRequester: FocusRequester,
    memory: FocusMemory,
    castListState: LazyListState,
    similarListState: LazyListState,
    recommendedListState: LazyListState,
) {
    val card = state.card ?: return
    val context = LocalContext.current

    // Provided at the scrolling container itself; see [rememberHeaderPageBringIntoViewSpec].
    val pageScrollState = rememberScrollState()
    var headerBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
            rememberHeaderPageBringIntoViewSpec(pageScrollState) { headerBottomPx },
    ) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(pageScrollState)) {
        // Backdrop sits behind content, not in a section above it: `matchParentSize()` fills
        // whatever height the foreground content needs so the hero fits the first viewport;
        // `heightIn(min = ...)` keeps short content from collapsing to a thin strip.
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp)) {
            if (card.backdropUrl != null) {
                AsyncImage(
                    model = card.backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                PlaceholderTile(name = card.title, modifier = Modifier.matchParentSize())
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, JellybeamTheme.Notte))),
            )

            Column(
                modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    text = card.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = JellybeamTheme.Archivo,
                        fontWeight = FontWeight.Bold,
                        color = JellybeamTheme.Panna,
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                    ),
                )

                val metaLine = buildList {
                    card.year?.let { add(it.toString()) }
                    displayRuntimeMinutes(state.runtimeMinutes)?.let { add(stringResource(R.string.discover_runtime_minutes, it)) }
                    if (state.genres.isNotEmpty()) add(state.genres.joinToString(", ") { it.name })
                }.joinToString("  ·  ")
                if (metaLine.isNotEmpty()) {
                    BasicText(text = metaLine, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 14.sp))
                }

                if (state.criticsScore != null || state.audienceScore != null) {
                    val scoreLine = listOfNotNull(
                        state.criticsScore?.let { stringResource(R.string.discover_score_critics, it) },
                        state.audienceScore?.let { stringResource(R.string.discover_score_audience, it) },
                    ).joinToString("  ·  ")
                    BasicText(text = scoreLine, style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 12.sp))
                }

                BasicText(
                    text = card.availability.detailLabel(),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = JellybeamTheme.Pistacchio, fontSize = 14.sp),
                )

                card.overview?.let { overview ->
                    BasicText(
                        text = overview,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 15.sp, lineHeight = 20.sp),
                    )
                }

                val actions = buildList {
                    if (card.jellyfinItemId != null) {
                        add(
                            ActionSpec(
                                id = ACTION_GO_TO_LIBRARY,
                                label = stringResource(R.string.discover_action_go_to_library),
                                onClick = { onGoToLibrary(card.jellyfinItemId!!) },
                                primary = true,
                            ),
                        )
                    }
                    if (state.activeRequest != null) {
                        add(
                            ActionSpec(
                                id = ACTION_CANCEL_REQUEST,
                                label = stringResource(R.string.discover_action_cancel_request),
                                // §4: capture the invoker before the confirm dialog opens.
                                onClick = { memory.captureInvoker(); viewModel.openCancelDialog() },
                            ),
                        )
                    }
                    if (state.canRequest) {
                        add(
                            ActionSpec(
                                id = ACTION_REQUEST,
                                label = stringResource(R.string.discover_action_request),
                                onClick = { memory.captureInvoker(); viewModel.startRequest(false) },
                                enabled = !state.isStartingRequest && !state.isSubmittingRequest,
                                primary = card.jellyfinItemId == null,
                            ),
                        )
                    }
                    if (state.canRequest4k) {
                        add(
                            ActionSpec(
                                id = ACTION_REQUEST_4K,
                                label = stringResource(R.string.discover_action_request_4k),
                                onClick = { memory.captureInvoker(); viewModel.startRequest(true) },
                                enabled = !state.isStartingRequest && !state.isSubmittingRequest,
                            ),
                        )
                    }
                    state.trailerUrl?.let { trailerUrl ->
                        add(
                            ActionSpec(
                                id = ACTION_TRAILER,
                                label = stringResource(R.string.discover_action_trailer),
                                onClick = {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
                                    } catch (_: ActivityNotFoundException) {
                                        // Fail open: no player installed for this link.
                                    }
                                },
                            ),
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // The action row closes the header for [rememberHeaderPageBringIntoViewSpec].
                    modifier = Modifier.onGloballyPositioned { headerBottomPx = it.boundsInRoot().bottom + pageScrollState.value },
                ) {
                    actions.forEachIndexed { index, action ->
                        var pillModifier: Modifier = Modifier.focusKey(memory, "action:${action.id}")
                        // First-open seed only (§2 rule 3's fallback); FocusRestorer owns every
                        // later restore.
                        if (index == 0) pillModifier = pillModifier.focusRequester(firstActionRequester)
                        ActionPill(
                            label = action.label,
                            onClick = action.onClick,
                            enabled = action.enabled,
                            primary = action.primary,
                            modifier = pillModifier,
                        )
                    }
                }
                state.requestError?.let { error ->
                    BasicText(text = error, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 13.sp))
                }
            }
        }

        // Vertical inset only: each shelf pads its own header and row so the rows run to the
        // screen edge and scroll like Discover's browse rows, instead of clipping at the margin.
        Column(
            modifier = Modifier.padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.seasons.isNotEmpty()) {
                SeasonList(state.seasons, modifier = Modifier.padding(horizontal = PAGE_MARGIN))
            }

            if (state.cast.isNotEmpty()) {
                PersonRefShelf(
                    title = stringResource(R.string.discover_cast_row_title),
                    people = state.cast,
                    listState = castListState,
                    onOpenPerson = onOpenPerson,
                    memory = memory,
                )
            }
            if (state.similar.isNotEmpty()) {
                CardShelf(
                    title = stringResource(R.string.discover_similar_row_title),
                    cards = state.similar,
                    listState = similarListState,
                    onOpenDetail = onOpenDetail,
                    keyPrefix = "shelf:$GROUP_SIMILAR/card:",
                    memory = memory,
                )
            }
            if (state.recommendations.isNotEmpty()) {
                CardShelf(
                    title = stringResource(R.string.discover_recommended_row_title),
                    cards = state.recommendations,
                    listState = recommendedListState,
                    onOpenDetail = onOpenDetail,
                    keyPrefix = "shelf:$GROUP_RECOMMENDED/card:",
                    memory = memory,
                )
            }
        }
    }
    }
}

/** Informational-only season list (availability per season); the *pickable* checkbox list lives
 * in [RequestOptionsDialog]. */
@Composable
private fun SeasonList(seasons: List<SeerrSeasonStatus>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        seasons.forEach { season ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(text = season.name, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 14.sp))
                BasicText(text = season.availability.detailLabel(), style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp))
            }
        }
    }
}

/** Shelf header at the page margin, matching the row's unscrolled content padding. */
@Composable
private fun ShelfTitle(title: String) {
    BasicText(
        text = title,
        modifier = Modifier.padding(horizontal = PAGE_MARGIN),
        style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 16.sp),
    )
}

/** Full-bleed shelf row: the margin is content padding, not a layout inset, so cards scroll to
 * the screen edge; the focused card pins at [PAGE_MARGIN] like Home's and Discover's rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfRow(listState: LazyListState, content: LazyListScope.() -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberShelfBringIntoViewSpec(startMargin = PAGE_MARGIN)) {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
            content = content,
        )
    }
}

@Composable
private fun PersonRefShelf(
    title: String,
    people: List<SeerrPersonRef>,
    listState: LazyListState,
    onOpenPerson: (Long) -> Unit,
    memory: FocusMemory,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfTitle(title)
        ShelfRow(listState) {
            itemsIndexed(people, key = { _, person -> person.personId }) { _, person ->
                val personKey = remember(person.personId) { person.personId.toString() }
                var isFocused by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .width(80.dp)
                        .focusKey(memory, "person:$personKey")
                        .onFocusChanged { isFocused = it.isFocused }
                        .clickable { onOpenPerson(person.personId) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.size(72.dp).focusRing(isFocused = isFocused, cornerRadius = 36.dp).clip(CircleShape).background(JellybeamTheme.SurfaceRaised)) {
                        if (person.profileUrl != null) {
                            AsyncImage(
                                model = person.profileUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    BasicText(
                        text = person.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 12.sp),
                    )
                    person.role?.let {
                        BasicText(
                            text = it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 11.sp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardShelf(
    title: String,
    cards: List<SeerrCard>,
    listState: LazyListState,
    onOpenDetail: (SeerrMediaType, Long) -> Unit,
    /** `"shelf:similar/card:"` or `"shelf:recommended/card:"` -- see [DiscoverDetailScreen] doc
     * for why the group is baked into the key. */
    keyPrefix: String,
    memory: FocusMemory,
) {
    var focusedIndex by remember(title) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShelfTitle(title)
        ShelfRow(listState) {
            itemsIndexed(cards, key = { _, card -> seerrCardKey(card.mediaType, card.tmdbId) }) { index, card ->
                val cardKey = remember(card.mediaType, card.tmdbId) { seerrCardKey(card.mediaType, card.tmdbId) }
                val isFocused by remember(index) { derivedStateOf { focusedIndex == index } }
                SeerrPosterCard(
                    posterUrl = card.posterUrl,
                    title = card.title,
                    year = card.year,
                    availability = card.availability,
                    isFocused = isFocused,
                    onClick = { onOpenDetail(card.mediaType, card.tmdbId) },
                    modifier = Modifier
                        .focusKey(memory, "$keyPrefix$cardKey")
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                focusedIndex = index
                            } else if (focusedIndex == index) {
                                focusedIndex = null
                            }
                        },
                )
            }
        }
    }
}

/** House pill-button recipe (fill-on-focus vocabulary shared with
 * [tv.jellybeam.ui.signin.SignInScreen]'s `SignInButton`/settings chips); `primary` spends the one
 * Pistacchio-filled action per row when idle. */
@Composable
private fun ActionPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, selected: Boolean = false) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // One green per meaning: focused pill is the only fill, `primary` is the only green border,
    // `selected` is accent text on the ordinary hairline chip.
    val background = if (isFocused) JellybeamTheme.Pistacchio else Color.Transparent
    val textColor = when {
        isFocused -> JellybeamTheme.Notte
        selected -> JellybeamTheme.Pistacchio
        else -> JellybeamTheme.Panna
    }

    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(50))
            .then(if (!isFocused) Modifier.border(1.dp, if (primary) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline, RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        // Every pill label is a single line, ellipsized, never letter-wrapped.
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = textColor, fontSize = 14.sp),
        )
    }
}

/**
 * [tv.jellybeam.ui.server.ServerManagementScreen]'s `RemoveConfirmation` recipe generalized for any
 * Yes/No confirmation. [dismissRequester] seeds focus on the dismiss action; the outer [Box]'s
 * `focusProperties { exit }` traps D-pad focus inside the overlay; [BackHandler] intercepts Back
 * first via LIFO dispatcher registration.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ConfirmDialog(
    focusGate: MutableState<Boolean>,
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissRequester = remember { FocusRequester() }
    // The RetainedScreenLayer's focus gate only admits placements made while it's held open; an
    // ungated requestFocus() from an overlay would return false forever.
    LaunchedEffect(Unit) { requestFocusUntilSuccess(focusGate) { dismissRequester.requestFocus() } }
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte.copy(alpha = 0.85f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(560.dp).background(JellybeamTheme.Surface, RoundedCornerShape(12.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(text = title, style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 22.sp))
            BasicText(text = body, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionPill(label = dismissLabel, onClick = onDismiss, enabled = !busy, modifier = Modifier.focusRequester(dismissRequester))
                ActionPill(label = confirmLabel, onClick = onConfirm, enabled = !busy, primary = true)
            }
        }
    }
}

/**
 * The season checkbox list (TV only) plus profile/root-folder pickers, shown only when
 * [DiscoverDetailUiState.requestOptions] has a server to pick from. A TV title can reach this
 * dialog with `requestOptions == null` since seasons must always be picked, so [options] is
 * nullable and every server/profile/folder block is skipped rather than treated as an error. A
 * non-requestable season renders checked and inert; select-all only affects requestable seasons;
 * submit is disabled until one requestable season is selected.
 *
 * [firstFocusRequester] seeds focus in priority order: first requestable season row, else first
 * rendered picker chip, else Cancel -- never a disabled control, since `requestFocus()` fails
 * forever on those. [seedFocusIfFirst] claims the shared requester for the first eligible
 * candidate. Same exit-trap and Back-dismisses-dialog handling as [ConfirmDialog].
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun RequestOptionsDialog(state: DiscoverDetailUiState, viewModel: DiscoverDetailViewModel, focusGate: MutableState<Boolean>) {
    val options = state.requestOptions
    val isTv = state.seasons.isNotEmpty()
    val submitEnabled = !state.isSubmittingRequest && (!isTv || canSubmitSeasonRequest(state.seasons, state.seasonSelection))

    val firstFocusRequester = remember { FocusRequester() }
    // Gated for the same reason as ConfirmDialog's seed.
    LaunchedEffect(Unit) { requestFocusUntilSuccess(focusGate) { firstFocusRequester.requestFocus() } }
    BackHandler(onBack = viewModel::dismissRequestDialog)

    // Fresh every recomposition -- exactly one candidate claims [firstFocusRequester] per pass,
    // whichever eligible element appears first in layout order.
    var firstFocusAssigned = false
    fun Modifier.seedFocusIfFirst(): Modifier {
        if (firstFocusAssigned) return this
        firstFocusAssigned = true
        return this.focusRequester(firstFocusRequester)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte.copy(alpha = 0.85f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(640.dp)
                // A long season list scrolls inside a capped panel height instead of stretching
                // the dialog off screen; the FlowRow wrap below means a short dialog reserves no
                // unused space.
                .heightIn(max = 520.dp)
                .background(JellybeamTheme.Surface, RoundedCornerShape(12.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(text = stringResource(R.string.discover_request_dialog_title), style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 22.sp))

            if (isTv) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogKicker(stringResource(R.string.discover_request_dialog_seasons))
                        ActionPill(label = stringResource(R.string.discover_request_dialog_select_all), onClick = viewModel::toggleSelectAllSeasons)
                    }
                    state.seasons.forEach { season ->
                        val checked = !season.requestable || state.seasonSelection[season.seasonNumber] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .let { if (season.requestable) it.seedFocusIfFirst() else it }
                                .clickable(enabled = season.requestable) { viewModel.toggleSeason(season) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SeasonCheckbox(checked = checked, inert = !season.requestable)
                            BasicText(
                                text = season.name,
                                modifier = Modifier,
                                style = TextStyle(
                                    fontFamily = JellybeamTheme.Archivo,
                                    color = if (season.requestable) JellybeamTheme.Panna else JellybeamTheme.Grigio,
                                    fontSize = 14.sp,
                                ),
                            )
                        }
                    }
                }
            }

            if (options != null && options.servers.size > 1) {
                PickerRow(
                    label = stringResource(R.string.discover_request_dialog_server),
                    options = options.servers,
                    selectedId = state.selectedServerId,
                    idOf = SeerrServiceServer::serverId,
                    nameOf = SeerrServiceServer::name,
                    firstChipModifier = Modifier.seedFocusIfFirst(),
                    onSelect = { viewModel.selectServer(it) },
                )
            }
            val activeServer = options?.servers?.firstOrNull { it.serverId == state.selectedServerId } ?: options?.servers?.firstOrNull()
            activeServer?.let { server ->
                if (server.profiles.size > 1) {
                    PickerRow(
                        label = stringResource(R.string.discover_request_dialog_profile),
                        options = server.profiles,
                        selectedId = state.selectedProfileId,
                        idOf = SeerrProfile::id,
                        nameOf = SeerrProfile::name,
                        firstChipModifier = Modifier.seedFocusIfFirst(),
                        onSelect = { viewModel.selectProfile(it) },
                    )
                }
                if (server.rootFolders.size > 1) {
                    PickerRow(
                        label = stringResource(R.string.discover_request_dialog_folder),
                        options = server.rootFolders,
                        selectedId = null,
                        idOf = { 0L },
                        nameOf = SeerrRootFolder::path,
                        selectedName = state.selectedRootFolder,
                        firstChipModifier = Modifier.seedFocusIfFirst(),
                        onSelectByName = { viewModel.selectRootFolder(it) },
                    )
                }
            }

            state.requestError?.let { error ->
                BasicText(text = error, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 13.sp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionPill(
                    label = stringResource(R.string.discover_request_dialog_cancel),
                    onClick = viewModel::dismissRequestDialog,
                    enabled = !state.isSubmittingRequest,
                    modifier = Modifier.seedFocusIfFirst(),
                )
                ActionPill(
                    label = stringResource(R.string.discover_request_dialog_submit),
                    onClick = viewModel::confirmRequestDialog,
                    enabled = submitEnabled,
                    primary = true,
                )
            }
        }
    }
}

/** The dialog's section labels share the sheets' own kicker voice (mono, small, muted, uppercase)
 * instead of a bold body line -- the label should whisper, the chips speak. */
@Composable
private fun DialogKicker(text: String) {
    BasicText(
        text = text.uppercase(java.util.Locale.US),
        style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Grigio, fontSize = 11.sp),
    )
}

@Composable
private fun SeasonCheckbox(checked: Boolean, inert: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(if (checked) JellybeamTheme.Pistacchio else Color.Transparent, RoundedCornerShape(4.dp))
            .border(1.dp, if (checked) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            BasicText(
                text = "✓",
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = if (inert) JellybeamTheme.Grigio else JellybeamTheme.Notte, fontSize = 12.sp),
            )
        }
    }
}

/**
 * A chip picker (server/profile), `id`-keyed. [FlowRow] wraps onto as many lines as needed rather
 * than overflowing the panel width. [firstChipModifier] applies to the selected chip (chip 0 if
 * none selected) -- see [RequestOptionsDialog]'s `seedFocusIfFirst`.
 */
// firstChipModifier targets the seeded chip, not the row root; ModifierParameter doesn't apply.
@SuppressLint("ModifierParameter")
@Composable
private fun <T> PickerRow(
    label: String,
    options: List<T>,
    selectedId: Long?,
    idOf: (T) -> Long,
    nameOf: (T) -> String,
    firstChipModifier: Modifier = Modifier,
    onSelect: (Long) -> Unit,
) {
    // Seeds the selected chip, falling back to chip 0 when nothing is selected.
    val seedIndex = options.indexOfFirst { idOf(it) == selectedId }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DialogKicker(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                ActionPill(
                    label = nameOf(option),
                    onClick = { onSelect(idOf(option)) },
                    selected = idOf(option) == selectedId,
                    modifier = if (index == seedIndex) firstChipModifier else Modifier,
                )
            }
        }
    }
}

/** [PickerRow]'s name-keyed overload -- [SeerrRootFolder] has no server-issued numeric id, only
 * a path. Same [FlowRow]/[firstChipModifier] treatment as the other overload. */
// firstChipModifier targets the seeded chip, not the row root; ModifierParameter doesn't apply.
@SuppressLint("ModifierParameter")
@Composable
private fun <T> PickerRow(
    label: String,
    options: List<T>,
    selectedId: Long?,
    idOf: (T) -> Long,
    nameOf: (T) -> String,
    selectedName: String?,
    firstChipModifier: Modifier = Modifier,
    onSelectByName: (String) -> Unit,
) {
    val seedIndex = options.indexOfFirst { nameOf(it) == selectedName }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DialogKicker(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                ActionPill(
                    label = nameOf(option),
                    onClick = { onSelectByName(nameOf(option)) },
                    selected = nameOf(option) == selectedName,
                    modifier = if (index == seedIndex) firstChipModifier else Modifier,
                )
            }
        }
    }
}
