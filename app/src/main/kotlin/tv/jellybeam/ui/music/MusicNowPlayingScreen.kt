package tv.jellybeam.ui.music

import android.os.SystemClock
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicPlaybackOwner
import tv.jellybeam.player.music.RepeatMode
import tv.jellybeam.player.music.TransportOp
import tv.jellybeam.ui.cards.ArtSource
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.focusRing
import tv.jellybeam.ui.common.ActiveWhileTop
import tv.jellybeam.ui.focus.FocusMemory
import tv.jellybeam.ui.focus.FocusRestorer
import tv.jellybeam.ui.focus.LocalFocusGate
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.ui.focus.placeByKeys
import tv.jellybeam.ui.focus.rememberFocusMemory
import tv.jellybeam.ui.focus.withPlacement
import uniffi.jellybeam_core.SpecLineContext
import uniffi.jellybeam_core.SyncedLyricLine
import uniffi.jellybeam_core.musicSpecLine
import uniffi.jellybeam_core.musicTranscodeLine

private val ART = 360.dp
private val ART_LYRICS = 240.dp
private val RIGHT_START = 460.dp
private val RIGHT_WIDTH = 460.dp
private val FACTS_TOP = 78.dp
private val QUEUE_LINE_TOP = 246.dp
private val FACTS_GAP = 20.dp
private val LYRICS_START = 360.dp
// Ends at the main layout's right edge (40 dp in), so the compact row fits Stop and ···.
private val LYRICS_WIDTH = 560.dp
private const val LYRICS_TRANSPORT_SCALE = 0.8f
private const val LYRIC_SCROLL_MS = 240
private val PastLyric = JellybeamTheme.Panna.copy(alpha = 0x40 / 255f)
private val NextLyric = JellybeamTheme.Panna.copy(alpha = 0x73 / 255f)

private const val KEY_SHUFFLE = "np-shuffle"
private const val KEY_PREVIOUS = "np-previous"
private const val KEY_PLAY = "np-play"
private const val KEY_NEXT = "np-next"
private const val KEY_REPEAT = "np-repeat"
private const val KEY_STRIP = "np-strip"
private const val KEY_ARTIST = "np-artist"
private const val KEY_QUEUE = "np-queue"
private const val KEY_LYRICS = "np-lyrics"
private const val KEY_STOP = "np-stop"
private const val KEY_VISUALIZER = "np-visualizer"
private const val KEY_FAVORITE = "np-favorite"
private const val KEY_MORE = "np-more"
private const val KEY_LYRIC_TEXT = "np-lyric-text"

/** Every Now Playing control's handler; built once per owner by the screen. */
internal data class NowPlayingActions(
    val onShuffle: () -> Unit,
    val onPrevious: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onRepeat: () -> Unit,
    val onSeek: (forward: Boolean) -> Unit,
    val onOpenArtist: (() -> Unit)?,
    val onOpenQueue: () -> Unit,
    val onToggleLyrics: () -> Unit,
    val onStop: () -> Unit,
    val onFavorite: () -> Unit,
    val onMore: () -> Unit,
    /** docs/30 §7: opens the visualizer; null when the device has no GLES 3.0. */
    val onVisualizer: (() -> Unit)? = null,
)

/**
 * docs/29 §9 F (F1–F3): full screen with no spine and no music bar; Back returns to where it was opened
 * from. Leaves on its own once nothing plays, since the screen shows a session only. It never keeps the screen on;
 * only ambient does (docs/29 §6).
 */
@Composable
internal fun MusicNowPlayingScreen(isTop: Boolean, nav: MusicNav, onOpenQueue: () -> Unit, onExit: () -> Unit) {
    val owner by AppGraph.musicStarted
    val playing = owner
    if (playing == null) LeaveWhenTop(isTop, onExit) else NowPlayingFor(playing, isTop, nav, onOpenQueue, onExit)
}

/** A music screen with no session to show pops itself, once it is top. */
@Composable
internal fun LeaveWhenTop(isTop: Boolean, onExit: () -> Unit) {
    LaunchedEffect(isTop) { if (isTop) onExit() }
}

@Composable
private fun NowPlayingFor(
    owner: MusicPlaybackOwner,
    isTop: Boolean,
    nav: MusicNav,
    onOpenQueue: () -> Unit,
    onExit: () -> Unit,
    viewModel: NowPlayingViewModel = viewModel(
        factory = NowPlayingViewModel.Factory(
            AppGraph.gateway,
            owner,
            directLine = { musicSpecLine(it, SpecLineContext.DIRECT_PLAY) },
            transcodeLine = ::musicTranscodeLine,
        ),
    ),
    menuModel: MusicMenuViewModel = rememberMusicMenu(),
) {
    val extras by viewModel.state.collectAsState()
    val menu by menuModel.state.collectAsState()
    ActiveWhileTop(isTop, viewModel::setActive)
    val flow by owner.state.collectAsState()
    val now by owner.now.collectAsState()
    val view = remember(flow, now) { musicQueueView(flow, now) }
    if (view == null) {
        LeaveWhenTop(isTop, onExit)
        return
    }
    val strings = rememberUiStrings()
    val model = remember(view, strings) { nowPlayingModel(view, owner::card, strings) }
    val settings by AppGraph.settingsStore.settings.collectAsState()
    val skipBackMs = (settings?.skipBackSecs?.toLong() ?: DEFAULT_SKIP_S) * 1_000L
    val skipForwardMs = (settings?.skipForwardSecs?.toLong() ?: DEFAULT_SKIP_S) * 1_000L
    val memory = rememberFocusMemory("music-now-playing")
    FocusRestorer(memory, isTop, fallback = { memory.target(KEY_PLAY) }, tag = "music-now-playing")

    var lyricsOn by rememberSaveable { mutableStateOf(false) }
    // docs/30 §7: the full-screen visualizer; closing it returns focus to its button by a gated placement.
    val context = LocalContext.current
    val canVisualize = remember { visualizerSupported(context) }
    // Transient like the other panels: a recreated Activity reopens on Now Playing, not the overlay.
    var visualizerOpen by remember { mutableStateOf(false) }
    var visualizerCloses by remember { mutableIntStateOf(0) }
    // docs/30 §7: a screen pushed over Now Playing closes the overlay, so the return restores focus normally.
    LaunchedEffect(isTop) { if (!isTop) visualizerOpen = false }
    var lyricsToggles by remember { mutableIntStateOf(0) }
    val lyricsShown = lyricsOn && extras.itemId == model.itemId && extras.lyrics != LyricsState.Absent
    // docs/15 §4: the swap drops the focused pill and focus with it, so the new Lyrics pill takes it by a gated placement.
    val focusGate = LocalFocusGate.current
    LaunchedEffect(lyricsToggles) {
        if (lyricsToggles == 0) return@LaunchedEffect
        memory.withPlacement { memory.placeByKeys(listOf(KEY_LYRICS), focusGate) }
    }
    LaunchedEffect(visualizerCloses) {
        if (visualizerCloses == 0) return@LaunchedEffect
        memory.withPlacement { memory.placeByKeys(listOf(KEY_VISUALIZER), focusGate) }
    }

    val position = rememberShownPosition(model.itemId, model.positionMs, model.durationMs, model.isPlaying)
    val isFavorite = extras.favorite?.takeIf { extras.itemId == model.itemId } ?: model.isFavorite
    val latestModel by rememberUpdatedState(model)
    val sessionEpoch = flow.session?.epoch
    val accounts by AppGraph.gateway.accountEpochs.collectAsState()
    val menuTarget = nowPlayingMenuTarget(owner.card(model.itemId), sessionEpoch, accounts.epoch)
    val latestMenuTarget by rememberUpdatedState(menuTarget)
    val latestPosition by rememberUpdatedState(position)
    val artistId = owner.card(model.itemId)?.albumArtistId
    val actions = remember(owner, viewModel, artistId, isFavorite, skipBackMs, skipForwardMs, canVisualize) {
        NowPlayingActions(
            onShuffle = { owner.send(MusicEvent.Transport(TransportOp.Shuffle(!latestModel.shuffle))) },
            onPrevious = { owner.send(MusicEvent.Transport(TransportOp.Previous)) },
            onPlayPause = { owner.send(if (latestModel.isPlaying) MusicEvent.Transport(TransportOp.Pause) else MusicEvent.UserPlay) },
            onNext = { owner.send(MusicEvent.Transport(TransportOp.Next)) },
            onRepeat = { owner.send(MusicEvent.Transport(TransportOp.Repeat(nextRepeat(latestModel.repeat)))) },
            onSeek = { forward ->
                val m = latestModel
                owner.send(MusicEvent.Transport(TransportOp.SeekTo(stripSeekTarget(latestPosition.value, m.durationMs, forward, skipBackMs, skipForwardMs))))
            },
            onOpenArtist = artistId?.let { id -> { nav.openArtist(id) } },
            onOpenQueue = onOpenQueue,
            onToggleLyrics = {
                lyricsOn = !lyricsOn
                lyricsToggles++
            },
            onStop = { owner.send(MusicEvent.Stop) },
            onFavorite = { viewModel.toggleFavorite(latestModel.itemId, isFavorite) },
            onMore = { latestMenuTarget?.let { menuModel.open(it, KEY_MORE) } },
            onVisualizer = if (canVisualize) ({ visualizerOpen = true }) else null,
        )
    }
    val panel = NowPlayingPanel(model, extras, isFavorite, position, (skipForwardMs / 1_000L).toInt(), itemActions = menuTarget != null, sessionEpoch)

    Box(Modifier.fillMaxSize().background(JellybeamTheme.Notte)) {
        if (lyricsShown) LyricsLayout(panel, actions, memory) else MainLayout(panel, actions, memory, playingFrom = playingFromLine(extras.source, strings))
        MusicActionPanel(
            state = menu,
            controls = menuModel.controls(),
            nav = nav,
            isTop = isTop,
            memory = memory,
            modifier = Modifier.align(Alignment.TopEnd),
            pageFavorite = PageFavorite(model.itemId, isFavorite, actions.onFavorite),
        )
        // Only while Now Playing is top: a screen pushed over it ends the surface and the ambient block.
        if (visualizerOpen && canVisualize && isTop) {
            VisualizerScreen(
                owner,
                playing = model.isPlaying,
                onPlayPause = actions.onPlayPause,
                onNextTrack = actions.onNext,
                onPreviousTrack = actions.onPrevious,
            ) {
                visualizerOpen = false
                visualizerCloses++
            }
        }
    }
}

private const val DEFAULT_SKIP_S = 10L

/** What both layouts draw from: the presenter's lines, the screen's reads and the running position. */
private class NowPlayingPanel(
    val model: NowPlayingModel,
    val extras: NowPlayingExtras,
    val isFavorite: Boolean,
    val position: State<Long>,
    val skipSeconds: Int,
    /** The heart and ···; absent while the session plays on a parked account. */
    val itemActions: Boolean,
    /** The session's account, which its art is read on. */
    val accountEpoch: ULong?,
) {
    val spec: SpecLine? get() = extras.spec.takeIf { extras.itemId == model.itemId }
    val hasLyrics: Boolean get() = extras.itemId == model.itemId && extras.lyrics != LyricsState.Absent
}

/** F1/F3: art left, everything about the song right. */
@Composable
private fun MainLayout(panel: NowPlayingPanel, actions: NowPlayingActions, memory: FocusMemory, playingFrom: String?) {
    val model = panel.model
    val accent = LocalAccent.current
    HeldArt(model.art, model.title, ART, panel.accountEpoch, Modifier.padding(start = 60.dp, top = 75.dp))
    Column(Modifier.padding(start = RIGHT_START, top = FACTS_TOP).width(RIGHT_WIDTH)) {
        // docs/29 §9 F1: the queue line sits at y=246 under one-line facts; a two-line title pushes it down, never under it.
        Column(Modifier.heightIn(min = QUEUE_LINE_TOP - FACTS_TOP - FACTS_GAP)) {
            if (playingFrom != null) BasicText(playingFrom, maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(accent.accentInk, 11))
            BasicText(
                model.title,
                Modifier.padding(top = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = archivo(JellybeamTheme.Panna, 34.sp).copy(lineHeight = 36.7.sp),
            )
            if (model.artistLine.isNotEmpty()) {
                ArtistLink(
                    PageLink(model.artistLine, actions.onOpenArtist),
                    modifier = Modifier.padding(top = 4.dp).focusKey(memory, KEY_ARTIST),
                    textSp = 17,
                    height = 26.dp,
                )
            }
            if (model.meta.isNotEmpty()) BasicText(model.meta, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(NextLyric, 14.sp, FontWeight.Normal))
            SpecLineText(panel.spec, Modifier.padding(top = 14.dp))
        }
        Spacer(Modifier.height(FACTS_GAP))
        BasicText(model.queueLine, maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(JellybeamTheme.Grigio, 9))
        StripAndTime(panel, actions, memory, Modifier.padding(top = 4.dp), timeSp = 13)
        TransportRow(model, actions, memory, scale = 1f, modifier = Modifier.padding(top = 24.dp))
        SecondaryRow(panel, actions, memory, lyricsOn = false, modifier = Modifier.padding(top = 20.dp))
        model.upNext?.let { next ->
            Row(Modifier.padding(top = 19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(stringResource(R.string.music_up_next).uppercaseUi(), style = monoStyle(JellybeamTheme.Grigio, 10))
                BasicText(next.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(PannaB3, 13.sp, FontWeight.Normal))
                next.duration?.let { BasicText(it, style = monoStyle(JellybeamTheme.Grigio, 10)) }
            }
        }
    }
}

/** F2: the art shrinks, the song facts move under it, and the lines take the right column. */
@Composable
private fun LyricsLayout(panel: NowPlayingPanel, actions: NowPlayingActions, memory: FocusMemory) {
    val model = panel.model
    HeldArt(model.art, model.title, ART_LYRICS, panel.accountEpoch, Modifier.padding(start = 60.dp, top = 60.dp))
    Column(Modifier.padding(start = 60.dp, top = 316.dp).width(ART_LYRICS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(model.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = archivo(JellybeamTheme.Panna, 20.sp).copy(lineHeight = 23.sp))
        if (model.artistLine.isNotEmpty()) BasicText(model.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(PannaB3, 15.sp, FontWeight.Medium))
        if (model.meta.isNotEmpty()) BasicText(model.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(NextLyric, 13.sp, FontWeight.Normal))
        SpecLineText(panel.spec, Modifier.padding(top = 10.dp))
    }
    Box(Modifier.padding(start = LYRICS_START, top = 70.dp).width(LYRICS_WIDTH).height(300.dp)) {
        when (val lyrics = panel.extras.lyrics) {
            is LyricsState.Synced -> SyncedLyrics(lyrics, panel.model)
            is LyricsState.Unsynced -> UnsyncedLyrics(lyrics, memory)
            LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots(color = PannaB3) }
            LyricsState.Absent -> Unit
        }
    }
    Column(Modifier.padding(start = LYRICS_START, top = 400.dp).width(LYRICS_WIDTH)) {
        StripAndTime(panel, actions, memory, Modifier, timeSp = 12)
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TransportRow(model, actions, memory, scale = LYRICS_TRANSPORT_SCALE)
            SecondaryRow(panel, actions, memory, lyricsOn = true)
        }
    }
}

/** docs/29 §11: Direct Play in GRIGIO; a transcode in Ambra, the only use of Ambra on music screens. */
@Composable
private fun SpecLineText(spec: SpecLine?, modifier: Modifier) {
    when (spec) {
        is SpecLine.Direct -> BasicText(spec.text, modifier, maxLines = 2, style = monoStyle(JellybeamTheme.Grigio, 10))
        is SpecLine.Transcode -> BasicText(spec.text, modifier, maxLines = 2, style = monoStyle(JellybeamTheme.Ambra, 10))
        null -> Unit
    }
}

/**
 * The queue strip and the time row under it. The strip is one focus target: Left/Right seek by the skip
 * settings and the time row shows the step; Down returns to Play/Pause rather than the nearest button.
 */
@Composable
private fun StripAndTime(panel: NowPlayingPanel, actions: NowPlayingActions, memory: FocusMemory, modifier: Modifier, timeSp: Int) {
    val model = panel.model
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val latest by rememberUpdatedState(actions)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .focusKey(memory, KEY_STRIP)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            latest.onSeek(event.key == Key.DirectionRight)
                            true
                        }
                        Key.DirectionDown -> memory.target(KEY_PLAY)?.requestFocus() == true
                        else -> false
                    }
                }
                .focusRing(focused, cornerRadius = 2.dp, color = accent.accentSheen, gap = 5.dp)
                .focusable(interactionSource = interaction),
        ) {
            QueueStrip(
                durationsMs = model.stripDurationsMs,
                current = model.stripCurrent,
                progress = { if (model.durationMs > 0L) panel.position.value.toFloat() / model.durationMs else 0f },
            )
        }
        TimeLine(panel, stripFocused = focused, timeSp = timeSp, modifier = Modifier.padding(top = 8.dp))
    }
}

/** `elapsed [PAUSED | ◂ 10 S ▸]` left, `−remaining │ ENDS h:mm` right; its own scope, so the tick recomposes only it. */
@Composable
private fun TimeLine(panel: NowPlayingPanel, stripFocused: Boolean, timeSp: Int, modifier: Modifier) {
    val model = panel.model
    val accent = LocalAccent.current
    val context = LocalContext.current
    val clock = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val row = nowPlayingTimeRow(panel.position.value, model.durationMs, model.paused, System.currentTimeMillis())
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(row.elapsed, style = monoStyle(JellybeamTheme.Panna, timeSp))
        when {
            stripFocused -> BasicText(stringResource(R.string.music_seek_step, panel.skipSeconds).uppercaseUi(), Modifier.padding(start = 9.dp), style = monoStyle(JellybeamTheme.Panna, timeSp))
            row.paused -> BasicText(stringResource(R.string.music_paused).uppercaseUi(), Modifier.padding(start = 9.dp), style = monoStyle(accent.accentInk, 10))
        }
        Box(Modifier.weight(1f))
        val ends = row.endsAtMs?.let { stringResource(R.string.music_ends_at, clock.format(java.util.Date(it))).uppercaseUi() }
        BasicText(listOfNotNull(row.remaining, ends).joinToString(" │ "), maxLines = 1, style = monoStyle(JellybeamTheme.Panna2, timeSp))
    }
}

/** docs/29 §8.5: shuffle · previous · play/pause · next · repeat; on toggles are SurfacePanel, never accent. */
@Composable
private fun TransportRow(model: NowPlayingModel, actions: NowPlayingActions, memory: FocusMemory, scale: Float, modifier: Modifier = Modifier) {
    val small = 44.dp * scale
    val smallGlyph = 18.dp * scale
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp * scale)) {
        TransportButton(ShuffleGlyph, actions.onShuffle, Modifier.focusKey(memory, KEY_SHUFFLE), size = small, glyphSize = smallGlyph, on = model.shuffle)
        TransportButton(PreviousGlyph, actions.onPrevious, Modifier.focusKey(memory, KEY_PREVIOUS), size = small, glyphSize = smallGlyph)
        TransportButton(
            glyph = if (model.isPlaying) PauseGlyph else PlayGlyph,
            onClick = actions.onPlayPause,
            modifier = Modifier.focusKey(memory, KEY_PLAY),
            size = 60.dp * scale,
            glyphSize = 26.dp * scale,
            primary = true,
            loading = model.loading,
        )
        TransportButton(NextGlyph, actions.onNext, Modifier.focusKey(memory, KEY_NEXT), size = small, glyphSize = smallGlyph)
        TransportButton(
            RepeatGlyph,
            actions.onRepeat,
            Modifier.focusKey(memory, KEY_REPEAT),
            size = small,
            glyphSize = smallGlyph,
            on = model.repeat != RepeatMode.Off,
            badge = "1".takeIf { model.repeat == RepeatMode.One },
        )
    }
}

/** Queue · Lyrics (only with lyrics; a 404 hides it) · Stop · ♥ · ··· (both only on the current account). */
@Composable
private fun SecondaryRow(panel: NowPlayingPanel, actions: NowPlayingActions, memory: FocusMemory, lyricsOn: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // docs/29 §9 F2: the lyrics pane's row is compact, as drawn, so ··· stays inside the column.
        MusicPill(stringResource(R.string.music_queue), QueueGlyph, primary = false, onClick = actions.onOpenQueue, modifier = Modifier.focusKey(memory, KEY_QUEUE), compact = lyricsOn)
        if (panel.hasLyrics) {
            MusicPill(
                stringResource(R.string.music_lyrics),
                glyph = null,
                primary = false,
                onClick = actions.onToggleLyrics,
                modifier = Modifier.focusKey(memory, KEY_LYRICS),
                selected = lyricsOn,
                compact = lyricsOn,
            )
        }
        // docs/30 §7: the compact lyrics row has no room for it; the main layout carries it.
        if (!lyricsOn) {
            actions.onVisualizer?.let { open ->
                MusicPill(stringResource(R.string.music_visualizer), glyph = null, primary = false, onClick = open, modifier = Modifier.focusKey(memory, KEY_VISUALIZER))
            }
        }
        // docs/29 §9 F: Stop ends the queue, so the music bar goes and this screen leaves on its own.
        MusicRoundButton(actions.onStop, accentFill = false, modifier = Modifier.focusKey(memory, KEY_STOP), compact = lyricsOn) { ink ->
            androidx.compose.foundation.Canvas(Modifier.size(15.dp)) { StopGlyph(ink) }
        }
        if (!panel.itemActions) return@Row
        MusicRoundButton(actions.onFavorite, accentFill = false, modifier = Modifier.focusKey(memory, KEY_FAVORITE), compact = lyricsOn) { ink ->
            val heart = if (panel.isFavorite && ink == JellybeamTheme.Panna) accent.accentInk else ink
            androidx.compose.foundation.Canvas(Modifier.size(15.dp)) { if (panel.isFavorite) HeartGlyph(heart) else HeartOutlineGlyph(heart) }
        }
        MusicRoundButton(actions.onMore, accentFill = false, modifier = Modifier.focusKey(memory, KEY_MORE), compact = lyricsOn) { ink ->
            BasicText("···", style = archivo(ink, 15.sp).copy(letterSpacing = 0.1.em))
        }
    }
}

/**
 * docs/29 §9 F2: the current line, run on from the player's last snapshot and woken at the next line's
 * start; a new snapshot restarts it, so it never reads a ticker that has stopped.
 */
@Composable
private fun rememberLyricLine(lines: List<SyncedLyricLine>, model: NowPlayingModel): State<Int> {
    val line = remember(lines) { mutableIntStateOf(lyricCue(lines, model.positionMs).line) }
    LaunchedEffect(lines, model.itemId, model.positionMs, model.durationMs, model.isPlaying) {
        val anchor = SystemClock.elapsedRealtime()
        while (true) {
            val cue = lyricCue(lines, musicPositionMs(model.positionMs, model.durationMs, model.isPlaying, SystemClock.elapsedRealtime() - anchor))
            line.intValue = cue.line
            if (!model.isPlaying) return@LaunchedEffect
            delay(cue.untilNextMs ?: return@LaunchedEffect)
            withFrameMillis { }
        }
    }
    return line
}

/**
 * docs/29 §9 F2: synced lines with the current one centred; each line change scrolls 240 ms on the
 * focus curve. Before the first line nothing is current and every line reads as next.
 */
@Composable
private fun SyncedLyrics(lyrics: LyricsState.Synced, model: NowPlayingModel) {
    val listState = rememberLazyListState()
    val current by rememberLyricLine(lyrics.lines, model)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight / 2
        LaunchedEffect(current) {
            val target = current.coerceAtLeast(0)
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (info == null) {
                listState.scrollToItem(target)
                return@LaunchedEffect
            }
            val center = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
            listState.animateScrollBy((info.offset + info.size / 2 - center).toFloat(), tween(LYRIC_SCROLL_MS, easing = JellybeamEasing))
        }
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(vertical = half),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            itemsIndexed(lyrics.lines, key = { i, _ -> i }, contentType = { _, _ -> "line" }) { i, line ->
                val style = when {
                    i == current -> archivo(JellybeamTheme.Panna, 24.sp)
                    i < current -> archivo(PastLyric, 19.sp, FontWeight.SemiBold)
                    else -> archivo(NextLyric, 19.sp, FontWeight.SemiBold)
                }
                BasicText(line.text, style = style.copy(lineHeight = style.fontSize * 1.25f))
            }
        }
    }
}

/** docs/29 §9 F2: unsynced lyrics are plain text the D-pad scrolls; at an edge the key moves focus on. */
@Composable
private fun UnsyncedLyrics(lyrics: LyricsState.Unsynced, memory: FocusMemory) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
    Column(
        Modifier
            .fillMaxSize()
            .focusKey(memory, KEY_LYRIC_TEXT)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val down = event.key == Key.DirectionDown
                if (!down && event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                if ((down && scroll.value >= scroll.maxValue) || (!down && scroll.value <= 0)) return@onPreviewKeyEvent false
                scope.launch { scroll.animateScrollBy(if (down) step else -step, tween(LYRIC_SCROLL_MS, easing = JellybeamEasing)) }
                true
            }
            .focusRing(focused, cornerRadius = 4.dp, color = LocalAccent.current.accentSheen)
            .focusable(interactionSource = interaction)
            .verticalScroll(scroll, enabled = false),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        lyrics.lines.forEach { BasicText(it, style = archivo(NextLyric, 19.sp, FontWeight.SemiBold).copy(lineHeight = 23.75.sp)) }
    }
}

/**
 * docs/29 §10 S1: Now Playing never skeletons the art. The last decoded art stays until the new one has
 * decoded, then the new one replaces it; no art at all is the no-art tile at once.
 */
@Composable
private fun HeldArt(source: ArtSource, name: String, size: Dp, accountEpoch: ULong?, modifier: Modifier) {
    val imageUrl = rememberMusicImageUrl(size, accountEpoch)
    val url = remember(source, imageUrl) { CardFormatting.resolveArtUrl(source, imageUrl) }
    var shownUrl by remember { mutableStateOf<String?>(null) }
    val slot = modifier.size(size).clip(RoundedCornerShape(2.dp))
    if (url == null) {
        NoArtTile(slot)
        return
    }
    val incoming = rememberAsyncImagePainter(model = url, contentScale = ContentScale.Crop, onSuccess = { shownUrl = url })
    val held = rememberAsyncImagePainter(model = shownUrl, contentScale = ContentScale.Crop)
    val ready = incoming.state is AsyncImagePainter.State.Success
    Box(slot.background(JellybeamTheme.Surface)) {
        Image(if (ready || shownUrl == null) incoming else held, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
