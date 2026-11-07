package tv.jellybeam.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.EnqueueMode
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.ui.detail.ActionPanelFrame
import tv.jellybeam.ui.detail.HeadingBand
import tv.jellybeam.ui.detail.PanelActionRow
import tv.jellybeam.ui.detail.PanelFooter
import tv.jellybeam.ui.detail.PanelTitle
import tv.jellybeam.ui.detail.panelFooterText
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.requestFocusWithRetry
import tv.jellybeam.ui.focus.withPlacement
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.MixSeedFfi

/** The page item's own heart, owned by its page: the menu reads and flips it there, so the two never differ. */
internal data class PageFavorite(val itemId: String, val isFavorite: Boolean, val onToggle: () -> Unit)

/** docs/29 §9 H: the queue's own row actions, given the row's timeline index and play position. */
internal data class QueueRowActions(val onPlayNext: (index: Int, song: Card) -> Unit, val onMove: (position: Int) -> Unit, val onRemove: (index: Int) -> Unit)

/** The menu's state handlers, from the screen's [MusicMenuViewModel]; grouped so the panel takes no view model. */
internal data class MusicMenuControls(
    val close: () -> Unit,
    val back: () -> Unit,
    val toggleFavorite: () -> Unit,
    val enterPlaylists: () -> Unit,
    val enterAllPlaylists: () -> Unit,
    val enterNewPlaylist: () -> Unit,
    val addTo: (playlist: Card, again: Boolean) -> Unit,
    val createAndAdd: (name: String) -> Unit,
    val removeFromPlaylist: (MusicMenuAction.RemoveFromPlaylist) -> Unit,
    val refreshMetadata: () -> Unit,
)

internal fun MusicMenuViewModel.controls(onRemoved: (MusicMenuAction.RemoveFromPlaylist) -> Unit = {}) = MusicMenuControls(
    close = ::close,
    back = ::back,
    toggleFavorite = ::toggleFavorite,
    enterPlaylists = ::enterPlaylists,
    enterAllPlaylists = ::enterAllPlaylists,
    enterNewPlaylist = ::enterNewPlaylist,
    addTo = ::addTo,
    createAndAdd = ::createAndAdd,
    removeFromPlaylist = { action -> removeFromPlaylist(action) { onRemoved(action) } },
    refreshMetadata = ::refreshMetadata,
)

/** One screen's ··· menu view model; its playlist writes land on the process scope and confirm in the bar. */
@Composable
internal fun rememberMusicMenu(): MusicMenuViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
    key = "music-menu",
    factory = MusicMenuViewModel.Factory(
        AppGraph.gateway,
        AppGraph.processScope,
        // An owner nothing built holds no card to update.
        favorited = { item, favorite -> AppGraph.musicStarted.value?.favoriteChanged(item, favorite) },
    ) { outcome ->
        // Only a session's bar can show it; an owner nothing built stays unbuilt.
        val owner = AppGraph.musicStarted.value ?: return@Factory
        when (outcome) {
            is PlaylistOutcome.Added -> owner.playlistAdded(outcome.playlist, outcome.again)
            is PlaylistOutcome.Failed -> owner.playlistFailed(outcome.playlist)
        }
    },
)

/**
 * docs/29 §9 I1/I2: what the control that starts [seed]'s mix shows, from the owner's pending request; Back
 * while it builds cancels it and the queue stays as it was. Reads the owner only once something built it.
 */
@Composable
internal fun rememberInstantMix(seed: MixSeedFfi?, isTop: Boolean): MixShown {
    val built by AppGraph.musicStarted
    val owner = built ?: return MixShown.Idle
    val flow by owner.state.collectAsState()
    val r = seed?.let { mixRequest(flow.pending, it) }
    var waited by remember(r) { mutableLongStateOf(0L) }
    LaunchedEffect(r) {
        if (r == null) return@LaunchedEffect
        delay(MIX_DOTS_AFTER_MS)
        waited = MIX_DOTS_AFTER_MS
    }
    BackHandler(enabled = isTop && r != null) { r?.let { owner.send(MusicEvent.CancelFetch(it)) } }
    return seed?.let { mixShown(flow.pending, it, waited) } ?: MixShown.Idle
}

/**
 * docs/29 §8.7/L1/E2/E3: the docs/19 panel for a music item; the caller pins it right, inside the bar's inset. Its first row
 * seeds focus at each level; closing hands focus back to [MusicMenuState.returnKey]. A screen that stops
 * being top (Go to album, a mix opening Now Playing) closes it, so a return never finds it open.
 */
@Composable
internal fun MusicActionPanel(
    state: MusicMenuState,
    controls: MusicMenuControls,
    nav: MusicNav,
    isTop: Boolean,
    memory: FocusMemory,
    modifier: Modifier = Modifier,
    pageFavorite: PageFavorite? = null,
    onReorder: (() -> Unit)? = null,
    queue: QueueRowActions? = null,
) {
    val target = state.target
    val latestControls by rememberUpdatedState(controls)
    val focusGate = LocalFocusGate.current
    val keyboard = LocalSoftwareKeyboardController.current
    var lastReturn by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target != null, state.returnKey) {
        if (target != null) {
            lastReturn = state.returnKey
            return@LaunchedEffect
        }
        val back = lastReturn ?: return@LaunchedEffect
        lastReturn = null
        // docs/15 §4: the panel's focused row left with it, so focus is nowhere and only a gated placement re-enters the layer.
        memory.withPlacement { memory.placeByKeys(listOf(back), focusGate) }
    }
    LaunchedEffect(isTop) { if (!isTop) latestControls.close() }
    if (target == null) return
    BlocksAmbient()

    val seed = remember(target) { mixSeed(target) }
    val mix = rememberInstantMix(seed.takeIf { target.context !is MusicMenuContext.InQueue }, isTop)
    BackHandler(enabled = isTop && mix !is MixShown.Building && mix !is MixShown.Waiting) { controls.back() }

    val favorite = pageFavorite?.takeIf { it.itemId == target.card.id }
    val first = remember { FocusRequester() }
    // All playlists swaps its New playlist row for the first list once the read lands, taking focus with it.
    val allLoaded = state.level == MusicMenuLevel.AllPlaylists && !(state.all as? PlaylistsLoad.Ready)?.playlists.isNullOrEmpty()
    val panelFocus = remember { mutableStateOf(false) }
    LaunchedEffect(target, state.level, allLoaded, isTop) {
        if (!isTop) return@LaunchedEffect
        // docs/15 §4: a level change removes the focused row, so the seed must open the layer's gate; the IME needs a focused field.
        // The drawer's content restorer may redirect the first request to its saved child, so the seed repeats until the panel holds focus.
        repeat(SEED_TRIES) {
            if (!requestFocusWithRetry(focusGate) { first.requestFocus() }) return@LaunchedEffect
            withFrameNanos { }
            if (panelFocus.value) {
                if (state.level == MusicMenuLevel.NewPlaylist) keyboard?.show()
                return@LaunchedEffect
            }
        }
    }

    ActionPanelFrame(
        offsetX = 0.dp,
        modifier = modifier.onFocusChanged { panelFocus.value = it.hasFocus },
        onPreviewKey = { event ->
            // docs/19 §1.5: Left closes, Right is consumed; the text field keeps both for its cursor.
            if (event.type != KeyEventType.KeyDown || state.level == MusicMenuLevel.NewPlaylist) return@ActionPanelFrame false
            when (event.key) {
                Key.DirectionLeft -> { controls.close(); true }
                Key.DirectionRight -> true
                else -> false
            }
        },
    ) {
        when (state.level) {
            MusicMenuLevel.First -> FirstLevel(
                state = state,
                target = target,
                isFavorite = favorite?.isFavorite ?: state.isFavorite,
                canReorder = onReorder != null,
                mix = mix,
                first = first,
                onAction = { action ->
                    runMenuAction(action, target, seed, controls, nav, onFavorite = favorite?.onToggle ?: controls.toggleFavorite, onReorder = onReorder, queue = queue)
                },
            )
            MusicMenuLevel.Playlists -> PlaylistLevel(target, state.recent, first, controls)
            MusicMenuLevel.AllPlaylists -> AllPlaylistsLevel(target, state.all, first, controls)
            MusicMenuLevel.NewPlaylist -> NewPlaylistLevel(target, first, controls)
        }
    }
}

private const val SEED_TRIES = 3

/** One row's effect; a mix keeps the panel open with its dots, everything else that leaves closes it first. */
private fun runMenuAction(
    action: MusicMenuAction,
    target: MusicMenuTarget,
    seed: MixSeedFfi,
    controls: MusicMenuControls,
    nav: MusicNav,
    onFavorite: () -> Unit,
    onReorder: (() -> Unit)?,
    queue: QueueRowActions?,
) {
    val context = target.context
    when (action) {
        MusicMenuAction.AddFavorite, MusicMenuAction.RemoveFavorite -> onFavorite()
        is MusicMenuAction.GoToAlbum -> { controls.close(); nav.openAlbum(action.albumId) }
        is MusicMenuAction.GoToArtist -> { controls.close(); nav.openArtist(action.artistId) }
        MusicMenuAction.ReorderSongs -> { controls.close(); onReorder?.invoke() }
        MusicMenuAction.PlayNext -> {
            controls.close()
            if (queue != null && context is MusicMenuContext.InQueue) {
                queue.onPlayNext(context.index, target.card)
            } else {
                nav.enqueue(EnqueueMode.PlayNext, enqueueSource(target), target.card.name)
            }
        }
        MusicMenuAction.AddToQueue -> { controls.close(); nav.enqueue(EnqueueMode.AddToQueue, enqueueSource(target), target.card.name) }
        MusicMenuAction.InstantMix -> nav.instantMix(seed)
        MusicMenuAction.AddToPlaylist -> controls.enterPlaylists()
        MusicMenuAction.RefreshMetadata -> controls.refreshMetadata()
        is MusicMenuAction.RemoveFromPlaylist -> controls.removeFromPlaylist(action)
        MusicMenuAction.Move -> { controls.close(); (context as? MusicMenuContext.InQueue)?.let { queue?.onMove?.invoke(it.position) } }
        MusicMenuAction.Remove -> { controls.close(); (context as? MusicMenuContext.InQueue)?.let { queue?.onRemove?.invoke(it.index) } }
    }
}

@Composable
private fun ColumnScope.FirstLevel(
    state: MusicMenuState,
    target: MusicMenuTarget,
    isFavorite: Boolean,
    canReorder: Boolean,
    mix: MixShown,
    first: FocusRequester,
    onAction: (MusicMenuAction) -> Unit,
) {
    val strings = rememberUiStrings()
    val model = remember(target, isFavorite, state.isAdministrator, canReorder, strings) {
        buildMusicMenu(strings, MusicMenuInput(target, isFavorite, state.isAdministrator, canReorder))
    }
    val noMix = (mix as? MixShown.NoMix)?.let { stringResource(it.line).uppercaseUi() }
    PanelTitle(kicker(stringResource(R.string.detail_menu_kicker_actions), back = false), target.card.name)
    Box(Modifier.height(15.dp))
    Column(Modifier.weight(1f)) {
        var index = 0
        model.groups.forEachIndexed { g, group ->
            HeadingBand(group.heading, isFirst = g == 0)
            group.rows.forEach { row ->
                val isMix = row.action == MusicMenuAction.InstantMix
                PanelActionRow(
                    label = row.label,
                    subtext = if (isMix) noMix else null,
                    drillIn = row.drillIn,
                    focusRequester = if (index++ == 0) first else null,
                    busy = isMix && mix == MixShown.Building,
                    onSelect = { onAction(row.action) },
                )
            }
        }
    }
    PanelFooter(panelFooterText(stringResource(R.string.detail_menu_footer_close).uppercaseUi(), pluralStringResource(R.plurals.detail_menu_footer_actions_count, model.rows.size, model.rows.size).uppercaseUi()))
}

@Composable
private fun ColumnScope.PlaylistLevel(target: MusicMenuTarget, load: PlaylistsLoad, first: FocusRequester, controls: MusicMenuControls) {
    val ready = load as? PlaylistsLoad.Ready
    val picks = remember(ready) { playlistPicks(ready?.playlists.orEmpty(), ready?.holding.orEmpty()) }
    val inPlaylist = stringResource(R.string.music_in_playlist).uppercaseUi()
    PanelTitle(kicker(stringResource(R.string.music_add_to_playlist), back = true), target.card.name)
    Box(Modifier.height(15.dp))
    Column(Modifier.weight(1f)) {
        picks.forEachIndexed { i, pick ->
            when (pick) {
                PlaylistPick.New -> PanelActionRow(
                    stringResource(R.string.music_new_playlist),
                    subtext = null,
                    gutter = true,
                    focusRequester = first,
                    onSelect = controls.enterNewPlaylist,
                )
                is PlaylistPick.Existing -> PanelActionRow(
                    pick.playlist.name,
                    subtext = if (pick.inPlaylist) inPlaylist else null,
                    gutter = true,
                    checked = pick.inPlaylist,
                    onSelect = { controls.addTo(pick.playlist, pick.inPlaylist) },
                )
                PlaylistPick.All -> PanelActionRow(stringResource(R.string.music_all_playlists), subtext = null, drillIn = true, gutter = true, onSelect = controls.enterAllPlaylists)
            }
            if (i == 0 && load == PlaylistsLoad.Loading) Box(Modifier.padding(start = 42.dp, top = 8.dp)) { LoadingDots() }
        }
    }
    PanelFooter(panelFooterText(stringResource(R.string.detail_menu_footer_back_for_actions).uppercaseUi(), null))
}

/** docs/29 §9 E2 "All playlists…": the one level that scrolls, as docs/19 §1.3's collections level does. */
@Composable
private fun ColumnScope.AllPlaylistsLevel(target: MusicMenuTarget, load: PlaylistsLoad, first: FocusRequester, controls: MusicMenuControls) {
    val lists = (load as? PlaylistsLoad.Ready)?.playlists.orEmpty()
    PanelTitle(kicker(stringResource(R.string.music_all_playlists), back = true), target.card.name)
    Box(Modifier.height(15.dp))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (lists.isEmpty()) {
            // Keeps the trap's focus while the list loads or when it can't be read.
            PanelActionRow(stringResource(R.string.music_new_playlist), subtext = null, gutter = true, focusRequester = first, onSelect = controls.enterNewPlaylist)
        }
        lists.forEachIndexed { i, list ->
            PanelActionRow(list.name, subtext = null, gutter = true, focusRequester = if (i == 0) first else null, onSelect = { controls.addTo(list, false) })
        }
    }
    PanelFooter(panelFooterText(stringResource(R.string.detail_menu_footer_back_for_actions).uppercaseUi(), null))
}

/**
 * docs/29 §9 E3: the name field sits at the top of the panel so the system keyboard never covers it;
 * Create and add is absent while the name is empty. The IME takes the first Back itself, ours returns to the list.
 */
@Composable
private fun ColumnScope.NewPlaylistLevel(target: MusicMenuTarget, field: FocusRequester, controls: MusicMenuControls) {
    NewPlaylistFields(
        nameKey = target.card.id,
        adds = stringResource(R.string.music_new_playlist_adds, target.card.name),
        createLabel = stringResource(R.string.music_create_and_add),
        footer = stringResource(R.string.detail_menu_footer_back_for_actions),
        field = field,
        onCreate = controls.createAndAdd,
        onCancel = controls.back,
    )
}

/**
 * docs/29 §9 A/E3 from the Playlists tab: the same name panel, creating an empty playlist. Back closes it;
 * while the create is at the server ([busy]) the panel stays and its rows are inert.
 */
@Composable
internal fun NewPlaylistPanel(
    isTop: Boolean,
    busy: Boolean,
    failed: Boolean,
    onCreate: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BlocksAmbient()
    val field = remember { FocusRequester() }
    val focusGate = LocalFocusGate.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(isTop) {
        if (!isTop) return@LaunchedEffect
        // docs/15 §4: a gated placement; the IME opens only once the field holds focus.
        if (requestFocusWithRetry(focusGate) { field.requestFocus() }) keyboard?.show()
    }
    BackHandler(enabled = isTop) { if (!busy) onClose() }
    ActionPanelFrame(offsetX = 0.dp, modifier = modifier, onPreviewKey = { false }) {
        NewPlaylistFields(
            nameKey = "",
            adds = if (failed) stringResource(R.string.music_couldnt_create) else null,
            createLabel = stringResource(R.string.music_create),
            footer = stringResource(R.string.detail_menu_footer_close),
            field = field,
            onCreate = { if (!busy) onCreate(it) },
            onCancel = { if (!busy) onClose() },
            busy = busy,
        )
    }
}

/** docs/29 §9 E3's content: the field, an optional line under the title, then Create (absent while blank) · Cancel. */
@Composable
private fun ColumnScope.NewPlaylistFields(
    nameKey: String,
    adds: String?,
    createLabel: String,
    footer: String,
    field: FocusRequester,
    onCreate: (String) -> Unit,
    onCancel: () -> Unit,
    busy: Boolean = false,
) {
    var name by rememberSaveable(nameKey) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val accent = LocalAccent.current
    val fieldInteraction = remember { MutableInteractionSource() }
    val fieldFocused by fieldInteraction.collectIsFocusedAsState()
    PanelTitle(kicker(stringResource(R.string.music_new_playlist), back = true), stringResource(R.string.music_new_playlist_name_it))
    Box(Modifier.height(6.dp))
    if (adds != null) {
        BasicText(
            adds,
            Modifier.padding(horizontal = 28.dp),
            maxLines = 2,
            style = archivo(JellybeamTheme.Grigio, 11.sp, androidx.compose.ui.text.font.FontWeight.Normal),
        )
    }
    Box(Modifier.height(12.dp))
    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .background(JellybeamTheme.Surface, RoundedCornerShape(8.dp))
            // The house field: Hairline at rest, accent only while the field itself holds focus.
            .border(2.dp, if (fieldFocused) accent.accentInk else JellybeamTheme.Hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                // The field moves focus on Down itself only for a hardware D-pad; injected and CEC keys stop in the text input.
                .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && focusManager.moveFocus(FocusDirection.Down) },
            textStyle = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 15.sp),
            singleLine = true,
            interactionSource = fieldInteraction,
            cursorBrush = SolidColor(accent.accentInk),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canCreatePlaylist(name)) onCreate(name) else keyboard?.hide() }),
        )
    }
    Box(Modifier.height(12.dp))
    Column(Modifier.weight(1f)) {
        if (canCreatePlaylist(name)) PanelActionRow(createLabel, subtext = null, busy = busy, onSelect = { onCreate(name) })
        PanelActionRow(stringResource(R.string.music_cancel), subtext = null, onSelect = onCancel)
    }
    PanelFooter(panelFooterText(footer.uppercaseUi(), null))
}

/** §1.5's kicker: `ACTIONS`, or `◂ LEVEL` one level down. */
@Composable
private fun kicker(label: String, back: Boolean) = buildAnnotatedString {
    if (back) withStyle(SpanStyle(color = JellybeamTheme.HairlineStrong)) { append("◂ ") }
    withStyle(SpanStyle(color = LocalAccent.current.accentInk)) { append(label.uppercaseUi()) }
}
