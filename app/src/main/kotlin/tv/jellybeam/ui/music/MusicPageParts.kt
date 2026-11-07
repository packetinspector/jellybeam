package tv.jellybeam.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.uppercaseUi
import tv.jellybeam.player.music.EnqueueMode
import tv.jellybeam.player.music.MusicSource
import tv.jellybeam.ui.cards.FOCUS_RING_EXTENT
import tv.jellybeam.ui.cards.focusRing
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.MixSeedFfi
import uniffi.jellybeam_core.SongSource

/** docs/29 §8: content starts at x=80 px on the 1920 canvas. */
internal val MUSIC_PAGE_START = 40.dp

/** Everything a music screen can ask of the app: navigation and playback through the music owner. */
internal data class MusicNav(
    val openAlbum: (albumId: String) -> Unit,
    val openArtist: (artistId: String) -> Unit,
    val openSongs: (source: SongSource, title: String) -> Unit,
    val openPlaylist: (playlist: Card) -> Unit,
    val play: (source: MusicSource, startIndex: Int) -> Unit,
    val shuffle: (source: MusicSource) -> Unit,
    val instantMix: (seed: MixSeedFfi) -> Unit,
    /** docs/29 §9 H, §3 Q5: Play next or Add to queue; with nothing playing it plays. [title] is what the bar confirms. */
    val enqueue: (mode: EnqueueMode, source: MusicSource, title: String) -> Unit,
)

/** The playing track and whether it plays now; `null` while no music session holds playback. */
internal data class PlayingTrack(val itemId: String, val isPlaying: Boolean)

/**
 * docs/29 §8.1: the Current dot's source. Reads the owner only once something built it
 * (`AppGraph.musicStarted`), so an unused feature costs nothing.
 */
@Composable
internal fun rememberPlayingTrack(): PlayingTrack? {
    val owner by AppGraph.musicStarted
    return rememberMusicBarSlice(owner) { PlayingTrack(it.itemId, it.isPlaying) }
}

/** The mark a row for [itemId] draws. */
internal fun PlayingTrack?.markFor(itemId: String): CurrentMark = when {
    this == null || this.itemId != itemId -> CurrentMark.None
    isPlaying -> CurrentMark.Playing
    else -> CurrentMark.Paused
}

/** Archivo at [size], for the page headers; [weight] defaults to bold. */
internal fun archivo(color: Color, size: TextUnit, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = weight, color = color, fontSize = size, letterSpacing = (-0.01).em)

/** One mono line, cased up; names never pass through here. */
@Composable
internal fun MonoLine(text: String, color: Color, sizeSp: Int = 11, modifier: Modifier = Modifier) {
    BasicText(text.uppercaseUi(), modifier, maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(color, sizeSp))
}

/** A page title clamped to two lines (docs/29 §10 S5). */
@Composable
internal fun PageTitle(text: String, sizeSp: Int, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = archivo(JellybeamTheme.Panna, sizeSp.sp).copy(lineHeight = (sizeSp * 1.1f).sp),
    )
}

private val PILL_HEIGHT = 36.dp

/** docs/29 §9 F2: the lyrics pane draws its secondary row compact (64 px tall, 26 px text), so it fits beside the transport. */
private val COMPACT_PILL_HEIGHT = 32.dp

/** docs/15 §0.3: a scrolling list's top padding, so its first row's focus ring draws inside the list's clip. */
internal val RING_CLEAR_TOP = FOCUS_RING_EXTENT + 2.dp
private val PILL_SHAPE = RoundedCornerShape(50)

/**
 * docs/29 §9 B/C action pills: [primary] is an accent fill (Play, Play all), the rest a hairline
 * ghost; [selected] (F2's Lyrics) is a SurfacePanel fill with a ✓, never accent; focused is accent with the sheen ring.
 * [modifier] goes before the focus target. [loading] swaps the glyph for the loading dots at its size (docs/29 §9 I1).
 */
@Composable
internal fun MusicPill(
    label: String,
    glyph: MusicGlyph?,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    loading: Boolean = false,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val filled = focused || primary
    val ink = if (filled) accent.onAccent else JellybeamTheme.Panna
    val height = if (compact) COMPACT_PILL_HEIGHT else PILL_HEIGHT
    val base = modifier
        .height(height)
        .focusRing(focused, cornerRadius = height / 2, color = accent.accentSheen)
    val look = when {
        filled -> base.background(accent.accent, PILL_SHAPE)
        selected -> base.background(JellybeamTheme.SurfacePanel, PILL_SHAPE).border(1.dp, JellybeamTheme.Hairline, PILL_SHAPE)
        else -> base.border(1.dp, JellybeamTheme.Hairline, PILL_SHAPE)
    }
    val mark = if (selected) CheckGlyph else glyph
    Row(
        modifier = look
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (primary) 22.dp else if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (loading) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { LoadingDots(color = ink) }
        } else if (mark != null) {
            Canvas(Modifier.size(14.dp)) { mark(ink) }
        }
        BasicText(label, maxLines = 1, style = archivo(ink, if (compact) 13.sp else 15.sp, if (primary) FontWeight.Bold else FontWeight.SemiBold))
    }
}

/** docs/29 §9 B/C round action: ♥ (ghost, the glyph accentInk when on) and ··· (accent fill, as drawn). */
@Composable
internal fun MusicRoundButton(onClick: () -> Unit, accentFill: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable (ink: Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val filled = focused || accentFill
    val size = if (compact) COMPACT_PILL_HEIGHT else PILL_HEIGHT
    val base = modifier
        .size(size)
        .focusRing(focused, cornerRadius = size / 2, color = accent.accentSheen)
    val look = if (filled) base.background(accent.accent, CircleShape) else base.border(1.dp, JellybeamTheme.Hairline, CircleShape)
    Box(look.clickable(interactionSource = interaction, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        content(if (filled) accent.onAccent else JellybeamTheme.Panna)
    }
}

/**
 * The page action row's handlers; [playLabel] is Play or Play all. [mix] is the page's own Instant Mix, and
 * [buildingLine] the artist page's `BUILDING INSTANT MIX` under the row (docs/29 §9 I1).
 */
internal data class PageActions(
    val playLabel: String,
    val isFavorite: Boolean,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val onInstantMix: () -> Unit,
    val onFavorite: () -> Unit,
    val onMore: () -> Unit,
    val mix: MixShown = MixShown.Idle,
    val buildingLine: Boolean = false,
)

/** The page's ··· door, so a closing menu hands focus back to it. */
internal const val KEY_PAGE_MORE = "page-more"

/**
 * docs/29 §9 B/C: Play · Shuffle · Instant Mix · ♥ · ···; [playModifier] lands on Play (the page's primary),
 * [moreModifier] on ···. A mix that is building or found nothing says so on one mono line under the row.
 */
@Composable
internal fun MusicActionRow(actions: PageActions, playModifier: Modifier, modifier: Modifier = Modifier, moreModifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicPill(actions.playLabel, PlayGlyph, primary = true, onClick = actions.onPlay, modifier = playModifier)
            MusicPill(stringResource(R.string.music_shuffle), ShuffleGlyph, primary = false, onClick = actions.onShuffle)
            MusicPill(stringResource(R.string.music_instant_mix), MixGlyph, primary = false, onClick = actions.onInstantMix, loading = actions.mix == MixShown.Building)
            MusicRoundButton(actions.onFavorite, accentFill = false) { ink ->
                val heart = if (actions.isFavorite && ink == JellybeamTheme.Panna) accent.accentInk else ink
                Canvas(Modifier.size(15.dp)) { if (actions.isFavorite) HeartGlyph(heart) else HeartOutlineGlyph(heart) }
            }
            MusicRoundButton(actions.onMore, accentFill = true, modifier = moreModifier) { ink ->
                BasicText("···", style = archivo(ink, 15.sp).copy(letterSpacing = 0.1.em))
            }
        }
        MixLine(actions.mix, actions.buildingLine, Modifier.padding(top = 8.dp, start = 4.dp))
    }
}

/** docs/29 §9 I1: `BUILDING INSTANT MIX` in accentInk where the page asks for it, `NO MIX FOR THIS … YET` in GRIGIO. */
@Composable
internal fun MixLine(mix: MixShown, buildingLine: Boolean, modifier: Modifier = Modifier) {
    when {
        mix is MixShown.NoMix -> MonoLine(stringResource(mix.line), JellybeamTheme.Grigio, 10, modifier)
        mix == MixShown.Building && buildingLine -> MonoLine(stringResource(R.string.music_building_mix), LocalAccent.current.accentInk, 10, modifier)
    }
}

/** docs/29 §8.1 extras: a shuffle mark (two crossing arrows). */
internal val ShuffleGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
    val down = Path().apply {
        moveTo(w * 0.08f, h * 0.25f)
        cubicTo(w * 0.45f, h * 0.25f, w * 0.5f, h * 0.75f, w * 0.88f, h * 0.75f)
    }
    val up = Path().apply {
        moveTo(w * 0.08f, h * 0.75f)
        cubicTo(w * 0.45f, h * 0.75f, w * 0.5f, h * 0.25f, w * 0.88f, h * 0.25f)
    }
    drawPath(down, tint, style = stroke)
    drawPath(up, tint, style = stroke)
    drawLine(tint, Offset(w * 0.75f, h * 0.12f), Offset(w * 0.9f, h * 0.25f), w * 0.11f, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.75f, h * 0.88f), Offset(w * 0.9f, h * 0.75f), w * 0.11f, StrokeCap.Round)
}

/** An Instant Mix mark: three bars of a level meter. */
internal val MixGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val bar = w * 0.16f
    listOf(0.55f to 0.18f, 0.25f to 0.42f, 0.4f to 0.66f).forEach { (top, x) ->
        drawLine(tint, Offset(w * x + bar / 2, h * top), Offset(w * x + bar / 2, h * 0.88f), bar, StrokeCap.Round)
    }
}

/** The ♥ outline, for an item that isn't a favorite. */
internal val HeartOutlineGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val heart = Path().apply {
        moveTo(w * 0.5f, h * 0.88f)
        cubicTo(w * 0.12f, h * 0.62f, w * 0.02f, h * 0.38f, w * 0.14f, h * 0.22f)
        cubicTo(w * 0.26f, h * 0.08f, w * 0.44f, h * 0.12f, w * 0.5f, h * 0.28f)
        cubicTo(w * 0.56f, h * 0.12f, w * 0.74f, h * 0.08f, w * 0.86f, h * 0.22f)
        cubicTo(w * 0.98f, h * 0.38f, w * 0.88f, h * 0.62f, w * 0.5f, h * 0.88f)
        close()
    }
    drawPath(heart, tint, style = Stroke(width = w * 0.1f))
}

/** docs/29 §10 S1: a SURFACE block at its content's final size; still, no shimmer. */
@Composable
internal fun MusicSkeleton(width: Dp, height: Dp, modifier: Modifier = Modifier, radius: Dp = 2.dp) {
    Box(modifier.size(width, height).background(JellybeamTheme.Surface, RoundedCornerShape(radius)))
}

/** docs/29 §12: content replacing a skeleton fades in over 180 ms; it starts opaque when it was there first. */
@Composable
internal fun FadeInBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(SKELETON_FADE_MS, easing = LinearEasing)) }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

private const val SKELETON_FADE_MS = 180

/** docs/29 §10 S2: the Curious mascot, one factual line and a mono detail; no button to nowhere. */
@Composable
internal fun MusicEmptyState(line: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.jb_mascot_curious),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.height(18.dp))
        BasicText(line, style = archivo(JellybeamTheme.Panna, 22.sp).copy(textAlign = TextAlign.Center))
        if (detail != null) {
            Spacer(Modifier.height(12.dp))
            MonoLine(detail, JellybeamTheme.Grigio)
        }
    }
}

/**
 * docs/29 §10 S3: the unreachable state, with Retry as its one control. [retryModifier] carries the
 * page's focus key, so Retry is where the page lands.
 */
@Composable
internal fun MusicUnreachable(host: String?, detail: String, onRetry: () -> Unit, retryModifier: Modifier, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Canvas(Modifier.size(36.dp)) { WifiOffGlyph(JellybeamTheme.Grigio) }
        val line = if (host != null) stringResource(R.string.music_unreachable, host) else stringResource(R.string.music_unreachable_server)
        BasicText(line, style = archivo(JellybeamTheme.Panna, 22.sp).copy(textAlign = TextAlign.Center))
        MonoLine(detail, JellybeamTheme.Grigio)
        Spacer(Modifier.height(10.dp))
        MusicPill(stringResource(R.string.music_retry), glyph = null, primary = true, onClick = onRetry, modifier = retryModifier)
    }
}

/** A Wi-Fi fan with a slash through it. */
private val WifiOffGlyph: MusicGlyph = { tint ->
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
    listOf(0.42f, 0.28f, 0.14f).forEach { r ->
        drawArc(tint, 220f, 100f, useCenter = false, topLeft = Offset(w * (0.5f - r * 1.6f), h * (0.78f - r * 1.6f)),
            size = androidx.compose.ui.geometry.Size(w * r * 3.2f, h * r * 3.2f), style = stroke)
    }
    drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.8f))
    drawLine(tint, Offset(w * 0.12f, h * 0.1f), Offset(w * 0.88f, h * 0.9f), w * 0.07f, StrokeCap.Round)
}

/** docs/29 §9 C: `DISC n` in mono with a 1px rule, 32 dp tall. */
@Composable
internal fun DiscHeader(number: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(32.dp).padding(start = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        MonoLine(stringResource(R.string.music_disc, number), JellybeamTheme.Grigio, sizeSp = 10)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(bottom = 4.dp).height(Dp.Hairline).background(JellybeamTheme.Hairline))
    }
}

/** docs/29 §9 C: a 2px hairline pill holding mono text or a verbatim name. */
@Composable
internal fun HairlineChip(text: String, mono: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = 232.dp)
            .height(if (mono) 26.dp else 24.dp)
            .border(1.dp, JellybeamTheme.Hairline, PILL_SHAPE)
            .padding(horizontal = if (mono) 12.dp else 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (mono) {
            BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = monoStyle(JellybeamTheme.Grigio, 10))
        } else {
            BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = archivo(PannaB3, 12.sp, FontWeight.Normal))
        }
    }
}

/**
 * docs/29 §9 A: the A–Z rail at the right edge, one focus target. Up/Down walk the letters that
 * have rows; OK or Left jumps to the letter's first row. Letters with rows are PANNA-2, without them
 * GRIGIO at 45%, the current one PANNA bold.
 */
@Composable
internal fun MusicIndexRail(
    entries: List<tv.jellybeam.ui.library.RailEntry>,
    currentOffset: Int,
    onJump: suspend (offset: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shown = tv.jellybeam.ui.library.IndexRailModel.entryIndexForOffset(entries, currentOffset)
    var cursor by androidx.compose.runtime.remember(entries) { androidx.compose.runtime.mutableIntStateOf(shown) }
    val current = if (focused) cursor else shown
    val latestJump by androidx.compose.runtime.rememberUpdatedState(onJump)
    // docs/16 §4.4: Left from the rail jumps into the list, so the drawer's Left gesture stands down while it holds focus.
    val leftSuppressed = tv.jellybeam.ui.nav.LocalDrawerLeftEdgeSuppressed.current
    androidx.compose.runtime.DisposableEffect(leftSuppressed, focused) {
        leftSuppressed?.value = focused
        onDispose { leftSuppressed?.value = false }
    }
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) cursor = shown }
    Column(
        modifier
            .focusRing(focused, cornerRadius = 6.dp, color = LocalAccent.current.accentSheen)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        val step = if (event.key == Key.DirectionDown) 1 else -1
                        var i = cursor + step
                        while (i in entries.indices && entries[i].count == 0) i += step
                        if (i !in entries.indices) return@onPreviewKeyEvent false
                        cursor = i
                        true
                    }
                    Key.DirectionLeft, Key.DirectionCenter, Key.Enter -> {
                        val entry = entries.getOrNull(cursor) ?: return@onPreviewKeyEvent false
                        scope.launch { latestJump(entry.offset) }
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEachIndexed { i, entry ->
            val color = when {
                i == current -> JellybeamTheme.Panna
                entry.count > 0 -> JellybeamTheme.Panna2
                else -> JellybeamTheme.Grigio.copy(alpha = 0.45f)
            }
            BasicText(entry.label, style = monoStyle(color, 11, if (i == current) FontWeight.Bold else FontWeight.Normal))
        }
    }
}
