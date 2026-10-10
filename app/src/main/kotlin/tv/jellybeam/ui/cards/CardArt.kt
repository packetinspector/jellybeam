package tv.jellybeam.ui.cards

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import tv.jellybeam.AppForeground
import tv.jellybeam.IMAGE_CROSSFADE_MS
import tv.jellybeam.JellybeamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.jellybeam_core.ImageKind

/** Focus/motion constants (docs/07 §6, spec §0.4); [RING_WIDTH] is 3dp, not spec's 2dp
 * (hardware-validated for couch-distance visibility).
 */
private const val FOCUS_SCALE = 1.04f
private const val ANIM_IN_MS = 180
private const val ANIM_OUT_MS = 240

/** §0.4's focus-scale timing, apart from [ANIM_IN_MS]/[ANIM_OUT_MS] (ring/brightness fades). */
private const val FOCUS_SCALE_ANIM_MS = 120
private val RING_WIDTH = 3.dp
private val RING_GAP = 3.dp
private val ART_RADIUS = 2.dp
private val ART_SHAPE = RoundedCornerShape(ART_RADIUS)

/** §0.4's brightness lift, approximated with a white wash (no single-call HSL lightness
 * transform); raised to 12% from spec's 8% for visibility, same reason as [RING_WIDTH].
 */
private const val FOCUS_BRIGHTNESS_LIFT = 0.12f

/**
 * [ArtBox]'s three focus-visual axes as pure functions, so they're unit-testable without Compose
 * and read only from draw/graphicsLayer lambdas, never in composition. Ring and lift share one
 * 0..1 [progress]; [scale] instead derives from its own [scaleProgress] -- see
 * [FOCUS_SCALE_ANIM_MS] for why it can't share their timing.
 */
internal data class FocusVisuals(val scale: Float, val ringAlpha: Float, val brightnessLift: Float)

internal fun focusVisuals(progress: Float, scaleProgress: Float): FocusVisuals {
    val p = progress.coerceIn(0f, 1f)
    val sp = scaleProgress.coerceIn(0f, 1f)
    return FocusVisuals(
        scale = 1f + (FOCUS_SCALE - 1f) * sp,
        ringAlpha = p,
        brightnessLift = FOCUS_BRIGHTNESS_LIFT * p,
    )
}

/** docs/07 §2/§6: skeleton pulse for pending loads. */
private const val SKELETON_PULSE_DURATION_MS = 2000
private const val SKELETON_PULSE_MIN_ALPHA = 0.85f
private const val SKELETON_PULSE_MAX_ALPHA = 1.0f

/** docs/07 §2: blurhash decode size -- tiny on purpose, shown scaled up and blurred-looking. */
private const val BLURHASH_DECODE_WIDTH = 32
private const val BLURHASH_DECODE_HEIGHT = 18

/**
 * A raw blurhash render is often brighter than the real image, so a freshly-pushed screen would
 * flash before the dark image loads. Dimmed toward Notte: [BLURHASH_DIM_DEFAULT] for card tiles,
 * [BACKDROP_PLACEHOLDER_DIM] for full-bleed hero/Detail backdrop bands.
 */
private const val BLURHASH_DIM_DEFAULT = 0.35f
const val BACKDROP_PLACEHOLDER_DIM = 0.55f

/** docs/07 §2: the interim chain stays hidden this long, so a cache-fast load fades straight in
 * instead of flashing flat tile, then blurhash, then art. */
private const val PLACEHOLDER_GRACE_MS = 250L

/** [CardArtImage]'s retry cap per URL, before giving up until the next foreground return. */
const val IMAGE_LOAD_MAX_RETRIES = 3

/** [CardArtImage]'s backoff base, doubled per attempt (2s/4s/8s) -- see [imageRetryDelayMs]. */
const val IMAGE_LOAD_RETRY_BASE_MS = 2000L

private val MAX_WIDTH_PARAM = Regex("[?&]maxWidth=(\\d+)")

/** The `maxWidth` the image URL asks the server for, i.e. the rendition's pixel width. */
internal fun urlMaxWidth(url: String): Int? = MAX_WIDTH_PARAM.find(url)?.groupValues?.get(1)?.toIntOrNull()

/** Height / width of a 16:9 rendition: backdrops, thumbs and episode stills. */
const val WIDE_ART_ASPECT = 9f / 16f

/** Height / width of a [source]'s rendition: Primary art is a 2:3 poster, everything else 16:9. */
internal fun renditionAspect(source: ArtSource): Float {
    val kind = (source as? ArtSource.Own)?.kind ?: (source as? ArtSource.Fallback)?.kind
    return if (kind == ImageKind.PRIMARY) 3f / 2f else WIDE_ART_ASPECT
}

/** The (width, height) Coil should decode [url] at, or `null` when the URL names no `maxWidth`. */
internal fun renditionSizePx(url: String, aspect: Float): Pair<Int, Int>? =
    urlMaxWidth(url)?.let { w -> w to (w * aspect).toInt().coerceAtLeast(1) }

/** Pure so the backoff schedule is unit-testable without Compose: 0 -> 2s, 1 -> 4s, 2 -> 8s. */
fun imageRetryDelayMs(attempt: Int): Long = IMAGE_LOAD_RETRY_BASE_MS shl attempt

/**
 * §6: every decode targets the same output size, so the hash string alone is a safe cache key.
 * Process-wide: the same hash recurs across shelves (Home, library grid, Detail).
 */
private val blurhashBitmapCache = BoundedLruCache<String, Bitmap>(64)

/**
 * The art slot every card is built from: a fixed-size box (so a focused sibling scaling up never
 * reflows the row) reacting to [isFocused] with a scale, a brightness lift and an accent ring clear
 * of the unscaled edge, with no shadow (docs/07). [content] paints the art plus any badges/progress
 * bar with it.
 */
@Composable
fun ArtBox(
    width: Dp,
    height: Dp,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Ring and lift share one Animatable; scale gets its own, on its own timing (§0.4
    // exception, see FOCUS_SCALE_ANIM_MS). Both launched from the same LaunchedEffect(isFocused)
    // as concurrent children, so a focus change starts (or reverses) them together -- one extra
    // coroutine only while a card is animating, none at rest. [focusVisuals] is read only inside
    // the layer/draw lambdas below.
    // Seeded from the first isFocused so a card mounted already focused snaps, never grows in.
    val progress = remember { Animatable(if (isFocused) 1f else 0f) }
    val scaleProgress = remember { Animatable(if (isFocused) 1f else 0f) }
    LaunchedEffect(isFocused) {
        launch {
            progress.animateTo(
                targetValue = if (isFocused) 1f else 0f,
                animationSpec = tween(if (isFocused) ANIM_IN_MS else ANIM_OUT_MS, easing = FastOutSlowInEasing),
            )
        }
        launch {
            scaleProgress.animateTo(
                targetValue = if (isFocused) 1f else 0f,
                animationSpec = tween(FOCUS_SCALE_ANIM_MS, easing = LinearOutSlowInEasing),
            )
        }
    }

    Box(
        modifier = modifier.size(width, height),
    ) {
        Box(
            modifier = Modifier
                .size(width, height)
                .graphicsLayer {
                    // Scale + clip in the one layer (§0.4); read inside the block only, never in
                    // composition, so a focus transition redraws this layer without recomposing.
                    val scale = focusVisuals(progress.value, scaleProgress.value).scale
                    scaleX = scale
                    scaleY = scale
                    shape = ART_SHAPE
                    clip = true
                }
                .background(JellybeamTheme.SurfaceRaised)
                .drawWithContent {
                    drawContent()
                    // White lift wash, drawn here instead of a separate Box+graphicsLayer.
                    val lift = focusVisuals(progress.value, scaleProgress.value).brightnessLift
                    if (lift > 0f) drawRect(Color.White, alpha = lift)
                },
        ) {
            content()
        }
        // Same discipline as the lift wash: ringAlpha read only inside drawWithContent.
        Box(
            modifier = Modifier
                .size(width, height)
                .drawWithContent {
                    drawContent()
                    val ringAlpha = focusVisuals(progress.value, scaleProgress.value).ringAlpha
                    if (ringAlpha <= 0f) return@drawWithContent
                    val strokeWidthPx = RING_WIDTH.toPx()
                    val outset = strokeWidthPx / 2f + RING_GAP.toPx()
                    drawRoundRect(
                        color = JellybeamTheme.Pistacchio.copy(alpha = ringAlpha),
                        topLeft = Offset(-outset, -outset),
                        size = Size(size.width + outset * 2, size.height + outset * 2),
                        style = Stroke(width = strokeWidthPx),
                        cornerRadius = CornerRadius(ART_RADIUS.toPx() + outset),
                    )
                },
        )
    }
}

/**
 * The same accent ring [ArtBox] draws, factored out as a plain [Modifier] so tab chips, hero
 * buttons and Settings rows share one width/outset geometry instead of hand-rolling a border.
 * Unlike ArtBox's ring this doesn't fade -- a boolean toggle is enough for non-scaling chrome.
 * [color] defaults to [JellybeamTheme.Pistacchio], overridable (e.g. [JellybeamTheme.Sheen] on an
 * already-Pistacchio control). Prepends `zIndex(1f)` while focused (docs/15 §0.3) so the ring,
 * drawn outside the element's layout bounds, paints after siblings regardless of paint order.
 */
fun Modifier.focusRing(isFocused: Boolean, cornerRadius: Dp = ART_RADIUS, color: Color = JellybeamTheme.Pistacchio): Modifier = this
    .zIndex(if (isFocused) 1f else 0f)
    .drawWithContent {
    drawContent()
    if (!isFocused) return@drawWithContent
    val strokeWidthPx = RING_WIDTH.toPx()
    val outset = strokeWidthPx / 2f + RING_GAP.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(-outset, -outset),
        size = Size(size.width + outset * 2, size.height + outset * 2),
        style = Stroke(width = strokeWidthPx),
        cornerRadius = CornerRadius(cornerRadius.toPx() + outset),
    )
}

/**
 * Process-wide skeleton pulse (docs/07 §6): one [Animatable] shared by every caller of
 * [rememberSkeletonPulseAlpha] instead of each giving itself its own infinite transition.
 * Whichever caller wins [tryClaim] drives the loop for as long as it stays composed; losing it
 * (composed away, or cancelled) hands the loop to the next caller reactively, and zero callers
 * means zero running animation.
 */
private object SkeletonPulse {
    val alpha = Animatable(SKELETON_PULSE_MIN_ALPHA)
    private val hasDriver = mutableStateOf(false)

    @Synchronized
    private fun tryClaim(): Boolean {
        if (hasDriver.value) return false
        hasDriver.value = true
        return true
    }

    @Synchronized
    private fun release() {
        hasDriver.value = false
    }

    suspend fun runWhileComposed() = coroutineScope {
        var driverJob: Job? = null
        try {
            snapshotFlow { hasDriver.value }.collect { claimed ->
                if (!claimed && driverJob?.isActive != true && tryClaim()) {
                    driverJob = launch {
                        try {
                            while (true) {
                                alpha.animateTo(SKELETON_PULSE_MAX_ALPHA, tween(SKELETON_PULSE_DURATION_MS, easing = LinearEasing))
                                alpha.animateTo(SKELETON_PULSE_MIN_ALPHA, tween(SKELETON_PULSE_DURATION_MS, easing = LinearEasing))
                            }
                        } finally {
                            release()
                        }
                    }
                }
            }
        } finally {
            driverJob?.cancel()
        }
    }
}

/** [SkeletonPulse.alpha] exposed as a [State] so a reader doesn't need the animation-core
 * [Animatable] type; the underlying read is still tracked, so this stays safe to poll in a draw
 * lambda without subscribing composition.
 */
private object SkeletonPulseAlphaState : State<Float> {
    override val value: Float get() = SkeletonPulse.alpha.value
}

/**
 * §6's skeleton pulse for [PulsingFlatTile]. Returns the raw [State] (not unwrapped with `by`)
 * so a function returning a derived `Float` can't be skipped, so every tick would recompose the
 * caller; read `.value` only inside a `graphicsLayer{}` lambda so the tick invalidates the draw
 * phase, not composition.
 */
@Composable
fun rememberSkeletonPulseAlpha(): State<Float> {
    LaunchedEffect(Unit) { SkeletonPulse.runWhileComposed() }
    return SkeletonPulseAlphaState
}

/**
 * One pulsing skeleton shape (same recipe as [PulsingFlatTile]) with a configurable corner radius,
 * covering tile, bar and circle shapes. A plain non-focusable [Box]: a skeleton must never be
 * reachable by D-pad navigation (docs/07 §6, docs/11 §Loading state).
 */
@Composable
internal fun SkeletonBlock(pulseAlpha: State<Float>, cornerRadius: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .graphicsLayer { alpha = pulseAlpha.value }
            .clip(RoundedCornerShape(cornerRadius))
            .background(JellybeamTheme.SurfaceRaised),
    )
}

/** docs/07 §2: missing artwork is a settled state, so the named fallback never animates. */
@Composable
fun PlaceholderTile(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(JellybeamTheme.SurfacePanel)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = name,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                color = JellybeamTheme.Grigio,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** docs/07 §2's other placeholder: pre-load flat SURFACE_RAISED + skeleton pulse, no text. */
@Composable
private fun PulsingFlatTile(modifier: Modifier = Modifier, animate: Boolean = true) {
    val animatedModifier = if (animate) {
        val pulseAlpha = rememberSkeletonPulseAlpha()
        modifier.graphicsLayer { alpha = pulseAlpha.value }
    } else {
        modifier
    }
    Box(modifier = animatedModifier.background(JellybeamTheme.SurfaceRaised))
}

/** Decodes [blurhash] off the main thread and paints it, falling back to [PulsingFlatTile] while
 * decoding, absent, or malformed (fail open).
 */
@Composable
private fun BlurhashOrPulsingTile(blurhash: String?, dimAlpha: Float, modifier: Modifier = Modifier, animate: Boolean = true) {
    if (blurhash == null) {
        PulsingFlatTile(modifier, animate)
        return
    }

    var bitmap by remember(blurhash) { mutableStateOf(blurhashBitmapCache.get(blurhash)) }
    LaunchedEffect(blurhash) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            Blurhash.decode(blurhash, BLURHASH_DECODE_WIDTH, BLURHASH_DECODE_HEIGHT)?.let { pixels ->
                Bitmap.createBitmap(pixels, BLURHASH_DECODE_WIDTH, BLURHASH_DECODE_HEIGHT, Bitmap.Config.ARGB_8888)
            }
        }
        decoded?.let { blurhashBitmapCache.put(blurhash, it) }
        bitmap = decoded
    }

    val decoded = bitmap
    if (decoded != null) {
        Box(modifier = modifier) {
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // The luminance-taming scrim (BLURHASH_DIM_DEFAULT); PulsingFlatTile needs none
            // (SurfaceRaised is already dark).
            if (dimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(JellybeamTheme.Notte.copy(alpha = dimAlpha)),
                )
            }
        }
    } else {
        PulsingFlatTile(modifier, animate)
    }
}

/**
 * The item's art via Coil, or [PlaceholderTile] when [source] resolves to nothing. While the Coil
 * request is in flight, shows docs/07 §2's interim chain underneath it ([blurhash] decoded, else
 * [PulsingFlatTile]).
 *
 * [imageUrl] crosses into blocking FFI, so it's [remember]ed rather than called in the body,
 * keyed on [imageUrl] itself (not just [source]) since callers bake kind/width into its closure.
 *
 * Coil never retries a failed load, and a card's composition outlives a single fetch, so a
 * transient failure would otherwise stick forever. One effect covers both triggers: a bounded
 * backoff (up to [IMAGE_LOAD_MAX_RETRIES], [imageRetryDelayMs]), pre-empted by an uncounted,
 * immediate retry on foreground return ([AppForeground.resumeCount]) if that comes first. Each
 * bumps `retryToken` so Coil treats it as a new fetch; the first request stays plain `url`.
 */
@Composable
fun CardArtImage(
    source: ArtSource,
    imageUrl: (itemId: String, kind: ImageKind, tag: String) -> String?,
    itemName: String,
    contentAlpha: Float,
    modifier: Modifier = Modifier,
    blurhash: String? = null,
    /** Dim for the blurhash placeholder; backdrop bands pass [BACKDROP_PLACEHOLDER_DIM]. */
    placeholderDimAlpha: Float = BLURHASH_DIM_DEFAULT,
    /** A memory-cache key for a same-item rendition already likely resident (docs/07 §1's shared
     * 240 bucket) -- shown instantly and crossfaded over on a hit; a miss falls through to the
     * blurhash chain unchanged.
     */
    placeholderMemoryCacheKey: MemoryCache.Key? = null,
    /** 0 when an outgoing image fades over this one (Home hero), since an empty grace dips to dark. */
    placeholderGraceMs: Long = PLACEHOLDER_GRACE_MS,
    /** Height / width of the server rendition; null derives it from the kind (Primary 3:2, else 16:9). */
    aspect: Float? = null,
    /** docs/07: a full-bleed backdrop fades in from the disk cache too, since popping in reads as a
     * flash; only a memory hit, already there on the first frame, skips the fade.
     */
    fadeFromDisk: Boolean = false,
) {
    // The ImageKind comes from the source case, never the caller: a fallback changes the image
    // type too.
    val url = remember(source, imageUrl) { CardFormatting.resolveArtUrl(source, imageUrl) }
    if (url == null) {
        PlaceholderTile(name = itemName, modifier = modifier.alpha(contentAlpha))
        return
    }

    // Only Error is ever written here (via Coil's onError, and cleared once a retry is
    // dispatched) -- Loading/Success never touch snapshot state, so a card recomposes at most
    // once per genuine error, not once per Coil callback.
    var errorState by remember(url) { mutableStateOf<AsyncImagePainter.State.Error?>(null) }

    // retryToken never resets, so the rebuilt model below stays distinct and Coil re-fetches
    // instead of reusing the failed result; retryAttempt is the bounded-backoff budget only --
    // a foreground-return retry resets it (see the effect below).
    var retryAttempt by remember(url) { mutableIntStateOf(0) }
    var retryToken by remember(url) { mutableIntStateOf(0) }
    LaunchedEffect(url, retryToken) {
        snapshotFlow { errorState }.filterNotNull().first()
        val resumeCountAtEntry = AppForeground.resumeCount.intValue
        val resumedFirst = if (retryAttempt < IMAGE_LOAD_MAX_RETRIES) {
            withTimeoutOrNull(imageRetryDelayMs(retryAttempt)) {
                snapshotFlow { AppForeground.resumeCount.intValue }.first { it != resumeCountAtEntry }
            } != null
        } else {
            // Backoff is exhausted; a foreground return is still an uncounted, unbounded trigger.
            snapshotFlow { AppForeground.resumeCount.intValue }.first { it != resumeCountAtEntry }
            true
        }
        retryAttempt = if (resumedFirst) 0 else retryAttempt + 1
        retryToken++
        errorState = null
    }

    // retryToken == 0 and no placeholder key keeps model == url exactly; a retry switches to an
    // ImageRequest carrying the bump as a non-cache-key parameter, so Coil sees a new request
    // without changing caching.
    val context = LocalContext.current
    val cacheKey = placeholderMemoryCacheKey
    // An explicit size (the rendition the URL asks for) lets Coil start at composition instead of
    // waiting for layout to measure the slot.
    val model = remember(url, retryToken, cacheKey, source, aspect, fadeFromDisk) {
        val builder = ImageRequest.Builder(context).data(url)
        // Coil's own crossfade factory already skips memory-cache hits.
        if (fadeFromDisk) builder.crossfade(IMAGE_CROSSFADE_MS)
        renditionSizePx(url, aspect ?: renditionAspect(source))?.let { (w, h) ->
            builder.size(w, h)
            builder.precision(Precision.INEXACT)
        }
        if (retryToken != 0) builder.setParameter("retry", retryToken, memoryCacheKey = null)
        if (cacheKey != null) builder.placeholderMemoryCacheKey(cacheKey)
        builder.build()
    }

    val painter = rememberAsyncImagePainter(
        model = model,
        contentScale = ContentScale.Crop,
        onError = { errorState = it },
    )
    // Collapses every intermediate Coil emission (Empty/Loading/Error) to one boolean, so this
    // only recomposes the card on the one transition that changes what's drawn underneath.
    val isSuccess by remember(painter) {
        derivedStateOf { painter.state is AsyncImagePainter.State.Success }
    }

    // Composed at once so the blurhash decodes during the grace, but drawn only after it; read in
    // graphicsLayer so the reveal redraws without recomposing the card.
    var placeholderShown by remember(url) { mutableStateOf(placeholderGraceMs <= 0L) }
    LaunchedEffect(url) {
        if (placeholderShown) return@LaunchedEffect
        delay(placeholderGraceMs)
        placeholderShown = true
    }

    Box(modifier = modifier.alpha(contentAlpha)) {
        if (!isSuccess) {
            BlurhashOrPulsingTile(
                blurhash = blurhash,
                dimAlpha = placeholderDimAlpha,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (placeholderShown) 1f else 0f },
                animate = errorState == null || retryAttempt < IMAGE_LOAD_MAX_RETRIES,
            )
        }
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** docs/07 §1: 3dp progress bar inside the art's bottom edge; fill=accent, track=accent@30%. */
@Composable
fun BoxScope.WatchProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(3.dp)
            .background(JellybeamTheme.ProgressTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .background(JellybeamTheme.Pistacchio),
        )
    }
}

/** docs/07 §1: watch-state badge, top-right, ~20dp -- unplayed count pill XOR watched checkmark. */
@Composable
fun BoxScope.WatchBadge(indicator: WatchIndicator) {
    when (indicator) {
        is WatchIndicator.Count -> Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(JellybeamTheme.Pistacchio, RoundedCornerShape(50)),
        ) {
            BasicText(
                text = indicator.count.toString(),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    fontWeight = FontWeight.SemiBold,
                    color = JellybeamTheme.Notte,
                    fontSize = 11.sp,
                ),
            )
        }

        WatchIndicator.WatchedCheck -> Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "✓",
                style = TextStyle(color = JellybeamTheme.Panna, fontSize = 12.sp),
            )
        }

        WatchIndicator.None -> Unit
    }
}

/**
 * §0.5's shared card-title block: fixed two-line reserved height, clipped with word-boundary
 * ellipsis on overflow. [CARD_TITLE_BLOCK_HEIGHT] (33dp) is [CARD_TITLE_LINE_HEIGHT] times two.
 */
@Composable
fun CardTitleText(title: String, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text = title,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.height(CARD_TITLE_BLOCK_HEIGHT),
        style = TextStyle(
            fontFamily = JellybeamTheme.Archivo,
            fontWeight = FontWeight.SemiBold,
            color = color,
            fontSize = CARD_TITLE_FONT_SIZE,
            lineHeight = CARD_TITLE_LINE_HEIGHT,
        ),
    )
}

/** §0.3's card title face. */
val CARD_TITLE_FONT_SIZE = 13.sp

/** §0.5: `lineHeight` 1.25x the face. */
val CARD_TITLE_LINE_HEIGHT = 16.25.sp

/** §0.5: two lines, reserved whether or not the title wraps. */
val CARD_TITLE_BLOCK_HEIGHT = 33.dp

/** §7's resume-shelf thumb pill: small rounded label docked top-right by default; not
 * Resume-specific (a poster's episode tag docks it bottom-start).
 */
@Composable
fun BoxScope.TimingPill(text: String, alignment: Alignment = Alignment.TopEnd) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(6.dp)
            .background(JellybeamTheme.Notte.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = JellybeamTheme.MartianMono,
                color = JellybeamTheme.Panna2,
                fontSize = 9.sp,
                letterSpacing = (-0.02).em,
            ),
        )
    }
}
