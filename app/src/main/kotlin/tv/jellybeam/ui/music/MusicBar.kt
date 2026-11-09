package tv.jellybeam.ui.music

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.nav.Screen
import tv.jellybeam.nav.hidesMusicBar
import tv.jellybeam.player.PageKeys
import tv.jellybeam.player.music.MusicEvent
import tv.jellybeam.player.music.MusicFlow
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.player.music.MusicPlaybackOwner
import tv.jellybeam.player.music.TransportOp
import tv.jellybeam.ui.cards.focusRing

/** docs/29 §8.4: the bar's controls row, 112 px at 1080p. */
private val MUSIC_BAR_ROW = 56.dp

/** Android TV title-safe bottom (5 %): the bar's surface runs to the edge, its controls and rings stay above this. */
internal val TV_SAFE_BOTTOM = 27.dp

/** The bar's whole height, controls row plus the title-safe strip under it. */
internal val MUSIC_BAR_HEIGHT = MUSIC_BAR_ROW + TV_SAFE_BOTTOM

/**
 * docs/29 §8.4: the room the music bar takes at the bottom of a layer, 0 while it is absent. Only the layer's
 * host pads by it (the drawer's content box, or a drawer-less layer's box), outside every scroller, so the focused
 * item and its ring are brought into view above the bar; a screen never reads it.
 */
internal val LocalMusicBarInset = compositionLocalOf { 0.dp }

/** docs/29 §8.4: [screen]'s bar room; a screen that hides the bar keeps none, so its layer never relayouts for it. */
internal fun musicBarInset(hasSession: Boolean, screen: Screen): Dp = if (hasSession && !screen.hidesMusicBar) MUSIC_BAR_HEIGHT else 0.dp

/**
 * [pick] of the owner's bar state, `null` when there is no owner or it holds no playback. docs/29 §7: the caller
 * recomposes only when the picked value changes, never on a position tick; the owner is read only once something
 * else built it (`AppGraph.musicStarted`), so an unused feature costs nothing.
 */
@Composable
internal fun <T> rememberMusicBarSlice(owner: MusicPlaybackOwner?, pick: (MusicBarModel) -> T): T? {
    if (owner == null) return null
    val flow = owner.state.collectAsState()
    val now = owner.now.collectAsState()
    val latestPick by rememberUpdatedState(pick)
    val slice by remember(owner) { derivedStateOf { musicBarModel(flow.value, now.value, owner::card)?.let(latestPick) } }
    return slice
}

/** docs/29 §8.4: whether a music session holds playback, so the bar and its inset show. */
@Composable
internal fun rememberHasMusicSession(owner: MusicPlaybackOwner?): Boolean = rememberMusicBarSlice(owner) { true } == true

/** docs/29 §9 G3: the drawer's Now playing line, the playing song's title and artist. */
@Composable
internal fun rememberNowPlayingTitle(owner: MusicPlaybackOwner?): Pair<String, String>? = rememberMusicBarSlice(owner) { it.title to it.artist }

/**
 * docs/29 §8.4: the bar with its own reads of [owner], so its once-a-second position recomposes the bar
 * alone (docs/29 §7). Draws nothing while the owner holds no playback.
 */
@Composable
internal fun MusicBarHost(
    owner: MusicPlaybackOwner,
    drawerOpen: Boolean,
    imageUrl: MusicImageUrl,
    onOpenNowPlaying: () -> Unit,
    onExitUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow by owner.state.collectAsState()
    val now by owner.now.collectAsState()
    val model = remember(owner, flow, now) { musicBarModel(flow, now, owner::card) } ?: return
    val notice = flow.notice
    val strings = rememberUiStrings()
    val latestNotice by rememberUpdatedState(notice)
    val latestPlaying by rememberUpdatedState(model.isPlaying)
    val latestOpen by rememberUpdatedState(onOpenNowPlaying)
    val latestExitUp by rememberUpdatedState(onExitUp)
    MusicBar(
        model = model,
        notice = notice?.let { musicNoticeText(it, strings) },
        drawerOpen = drawerOpen,
        imageUrl = imageUrl,
        actions = remember(owner) {
            musicBarActions(
                owner = owner,
                isPlaying = { latestPlaying },
                onOpenNowPlaying = { latestOpen() },
                onExitUp = { latestExitUp() },
                onNoticeShown = { latestNotice?.let(owner::noticeShown) },
            )
        },
        // The bar is music's control wherever it shows, so its page keys change track too.
        modifier = modifier.musicTrackKeys(),
    )
}

/** docs/29 §9 I1: a mix that starts a session opens Now Playing on it, wherever it was pressed. */
@Composable
internal fun OpenNowPlayingOnMix(owner: MusicPlaybackOwner?, onOpen: () -> Unit) {
    if (owner == null) return
    val latestOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(owner) {
        var before = owner.state.value
        owner.state.collect { after ->
            if (mixStarted(before, after)) latestOpen()
            before = after
        }
    }
}

/** docs/29 §9 J1: the remote's Play key on a Home album card; a press on the playing album toggles it. */
internal fun pressPlayOnAlbum(albumId: String) {
    val owner = AppGraph.musicStarted.value
    when (albumPlayKey(owner?.state?.value, owner?.now?.value?.isPlaying == true, albumId)) {
        AlbumPlayKey.Play -> AppGraph.music.play(MusicSource.Album(albumId))
        AlbumPlayKey.Pause -> owner?.send(MusicEvent.Transport(TransportOp.Pause))
        AlbumPlayKey.Resume -> owner?.send(MusicEvent.UserPlay)
    }
}

/** The bar's handlers; [onExitUp] returns focus to where it came from (Up and Back, G2). */
internal data class MusicBarActions(
    val onPlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onStop: () -> Unit,
    val onOpenNowPlaying: () -> Unit,
    val onExitUp: () -> Unit,
    val onNoticeShown: () -> Unit,
)

/** Play/Pause, Previous, Next and Stop through the owner's reducer; Play after a lost connection retries (MusicEvent.UserPlay). */
internal fun musicBarActions(
    owner: MusicPlaybackOwner,
    isPlaying: () -> Boolean,
    onOpenNowPlaying: () -> Unit,
    onExitUp: () -> Unit,
    onNoticeShown: () -> Unit,
) = MusicBarActions(
    onPlayPause = { owner.send(if (isPlaying()) MusicEvent.Transport(TransportOp.Pause) else MusicEvent.UserPlay) },
    onPrevious = { owner.send(MusicEvent.Transport(TransportOp.Previous)) },
    onNext = { owner.send(MusicEvent.Transport(TransportOp.Next)) },
    // The bar leaves with the queue, so focus goes back to the page first rather than to nothing.
    onStop = {
        onExitUp()
        owner.send(MusicEvent.Stop)
    },
    onOpenNowPlaying = onOpenNowPlaying,
    onExitUp = onExitUp,
    onNoticeShown = onNoticeShown,
)

/** What a page key does on a music screen (docs/29 §9 Keys). */
internal enum class TrackKey { NEXT, PREVIOUS, CONSUME, PASS }

/**
 * docs/29 §9 Keys: Page/Channel Up is the next track and Down the previous (as for video chapters, docs/12 §9),
 * once per press; the repeats and release are consumed so a held key skips one track. Without a session
 * ([hasSession] false, e.g. after Stop) they pass, so a list can page with them again.
 */
internal fun musicTrackKey(key: Key, down: Boolean, repeatCount: Int, hasSession: Boolean): TrackKey = when {
    !hasSession || !PageKeys.isPageKey(key) -> TrackKey.PASS
    !down || repeatCount != 0 -> TrackKey.CONSUME
    key in PageKeys.NEXT_KEYS -> TrackKey.NEXT
    else -> TrackKey.PREVIOUS
}

/**
 * docs/29 §9 Keys: the music layers' page keys change track through the playing owner, ahead of their children:
 * a scroll container would otherwise take Page Up/Down whenever it can scroll. No session: they pass.
 */
internal fun Modifier.musicTrackKeys(): Modifier = onPreviewKeyEvent { event ->
    val owner = AppGraph.musicStarted.value ?: return@onPreviewKeyEvent false
    // Transport acts only on an Active session (MusicFlow), so Idle and Starting let the keys page.
    val hasSession = owner.state.value.phase is MusicFlow.Phase.Active
    when (musicTrackKey(event.key, event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount, hasSession)) {
        TrackKey.NEXT -> owner.send(MusicEvent.Transport(TransportOp.Next))
        TrackKey.PREVIOUS -> owner.send(MusicEvent.Transport(TransportOp.Previous))
        TrackKey.CONSUME -> Unit
        TrackKey.PASS -> return@onPreviewKeyEvent false
    }
    true
}

private val BUTTON = 32.dp
private val BUTTON_GLYPH = 15.dp

/**
 * docs/29 §8.4/G1-G3: the persistent bar, bottom-anchored right of the drawer spine, or of the open
 * drawer ([drawerOpen], which then also keeps focus out of it). Down from a page enters on Play/Pause;
 * Left walks to Previous and the art and title block (OK opens Now Playing); Up and Back hand focus back.
 * Only the focused control changes on focus. [notice] replaces line 2 for 3 s, then [MusicBarActions.onNoticeShown].
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MusicBar(
    model: MusicBarModel,
    notice: String?,
    drawerOpen: Boolean,
    imageUrl: MusicImageUrl,
    actions: MusicBarActions,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val start by animateDpAsState(
        if (drawerOpen) DRAWER_EDGE else SPINE_EDGE,
        tween(if (drawerOpen) DRAWER_OPEN_MS else DRAWER_CLOSE_MS),
        label = "musicBarStart",
    )
    val playRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val latestActions by rememberUpdatedState(actions)
    val position = rememberShownPosition(model.itemId, model.positionMs, model.durationMs, model.isPlaying)

    BackHandler(enabled = hasFocus) { latestActions.onExitUp() }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        latestActions.onNoticeShown()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MUSIC_BAR_HEIGHT)
            .padding(start = start)
            .background(JellybeamTheme.Surface)
            .drawBehind {
                // docs/29 §8.4: a 2 dp accent progress line on a hairline track along the top edge.
                val h = 2.dp.toPx()
                drawRect(JellybeamTheme.Hairline, size = Size(size.width, h))
                val fraction = if (model.durationMs > 0L) position.value.toFloat() / model.durationMs else 0f
                drawRect(accent.accent, size = Size(size.width * fraction.coerceIn(0f, 1f), h))
            }
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                latestActions.onExitUp()
                true
            }
            .focusProperties { enter = { if (drawerOpen) FocusRequester.Cancel else playRequester } }
            .focusGroup()
            .padding(start = 21.dp, end = 32.dp, bottom = TV_SAFE_BOTTOM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NowPlayingBlock(model, notice, imageUrl, actions.onOpenNowPlaying, Modifier.weight(1f))
        TimeReadout(position, model.durationMs)
        TransportButton(PreviousGlyph, actions.onPrevious, size = BUTTON, glyphSize = BUTTON_GLYPH)
        TransportButton(
            glyph = if (model.isPlaying) PauseGlyph else PlayGlyph,
            onClick = actions.onPlayPause,
            modifier = Modifier.focusRequester(playRequester),
            size = BUTTON,
            glyphSize = BUTTON_GLYPH,
            loading = model.loading,
        )
        TransportButton(NextGlyph, actions.onNext, size = BUTTON, glyphSize = BUTTON_GLYPH)
        // docs/29 §8.4: Stop ends the queue, so the bar itself goes.
        TransportButton(StopGlyph, actions.onStop, size = BUTTON, glyphSize = BUTTON_GLYPH)
    }
}

/** `m:ss / m:ss`; its own scope, so the once-a-second tick recomposes only this text. */
@Composable
private fun TimeReadout(position: State<Long>, durationMs: Long) {
    BasicText(
        text = stringResource(R.string.music_time_pair, formatTrackTime(position.value), formatTrackTime(durationMs)),
        modifier = Modifier.padding(end = 6.dp),
        maxLines = 1,
        style = monoStyle(JellybeamTheme.Panna2, 11),
    )
}

/** The art and the two text lines; focusable as one block, ringed (never filled) on focus. */
@Composable
private fun NowPlayingBlock(model: MusicBarModel, notice: String?, imageUrl: MusicImageUrl, onOpen: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxHeight()
            // The ring draws outside the block, so 6 dp keeps it off the progress line and inside the title-safe line.
            .padding(vertical = 6.dp)
            .focusRing(focused, cornerRadius = 4.dp, color = accent.accentInk)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SquareArt(model.art, imageUrl, model.title, Modifier.size(40.dp).clip(RoundedCornerShape(2.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CurrentDot(color = accent.accentInk, modifier = Modifier.padding(end = 7.dp), pulsing = model.isPlaying)
                BasicText(
                    text = model.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Medium, color = JellybeamTheme.Panna, fontSize = 14.sp),
                )
            }
            when (val line = musicBarLine2(model, notice)) {
                is BarLine2.Artist -> BasicText(
                    text = line.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = PannaB3, fontSize = 12.sp),
                )
                // docs/29 §8.4: the bar's own state lines are accentInk mono, never Ambra.
                is BarLine2.Notice -> BasicText(line.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(accent.accentInk, 11))
                BarLine2.ConnectionLost -> BasicText(
                    text = stringResource(R.string.music_bar_connection_lost).uppercaseUi(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = monoStyle(accent.accentInk, 11),
                )
            }
        }
    }
}

/**
 * The position to show: the player publishes on events only, so it runs on from the last snapshot once a
 * second while playing. Each tick waits for a frame, so it stops with the window's frame clock (screen
 * off, Activity stopped).
 */
@Composable
internal fun rememberShownPosition(itemId: String, positionMs: Long, durationMs: Long, isPlaying: Boolean): State<Long> {
    val anchor = remember(itemId, positionMs, isPlaying) { SystemClock.elapsedRealtime() }
    val shown = remember(anchor) { mutableLongStateOf(positionMs) }
    LaunchedEffect(anchor, positionMs, durationMs, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            val ran = musicPositionMs(positionMs, durationMs, isPlaying = true, SystemClock.elapsedRealtime() - anchor)
            delay(1_000L - ran % 1_000L)
            withFrameMillis { }
            shown.longValue = musicPositionMs(positionMs, durationMs, isPlaying = true, SystemClock.elapsedRealtime() - anchor)
        }
    }
    return shown
}

private const val NOTICE_MS = 3_000L
private val SPINE_EDGE: Dp = tv.jellybeam.ui.nav.MenuSpine.WIDTH
private val DRAWER_EDGE: Dp = tv.jellybeam.ui.nav.DRAWER_WIDTH
private const val DRAWER_OPEN_MS = tv.jellybeam.ui.nav.MenuSpine.OPEN_MS
private const val DRAWER_CLOSE_MS = tv.jellybeam.ui.nav.MenuSpine.CLOSE_MS
