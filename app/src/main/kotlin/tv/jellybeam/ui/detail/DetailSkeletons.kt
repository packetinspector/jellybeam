package tv.jellybeam.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.ui.cards.SkeletonBlock
import tv.jellybeam.ui.cards.rememberSkeletonPulseAlpha

// docs/11 §Loading state: every above-the-fold region whose data is still loading draws a quiet
// pulsing skeleton at its final size, so nothing below it moves when the content arrives. Text
// regions measure the same [TextStyle] object the real text uses; the rest share the real
// composables' constants. Skeletons are plain Boxes, never focus targets.

/** Content fades in over this long when it replaces a skeleton (docs/11 §Loading state). */
private const val REGION_FADE_MS = 150

private val SKELETON_BAR_RADIUS = 2.dp

/** Vertical room kept clear inside a text-line bar so a bar reads as text, not a slab. */
private val SKELETON_BAR_INSET = 3.dp

/** Fade-in progress for one region: 1 from the first frame when [show] starts as content, else
 * 0 until the content replaces a skeleton, then 150 ms up to 1. Read it only inside
 * `graphicsLayer {}` so the animation never recomposes the page.
 */
@Composable
internal fun rememberRegionAlpha(show: RegionShow): State<Float> {
    val fade = remember { Animatable(if (show == RegionShow.CONTENT) 1f else 0f) }
    val isContent = show == RegionShow.CONTENT
    LaunchedEffect(isContent) {
        if (isContent) fade.animateTo(1f, tween(REGION_FADE_MS, easing = LinearEasing)) else fade.snapTo(0f)
    }
    return fade.asState()
}

/**
 * One region's three states: [skeleton] while loading, [content] faded in when it arrives, nothing
 * when it settled empty. Content is composed only when there is some, so nothing focusable ever
 * sits under a skeleton.
 */
@Composable
internal fun RegionReveal(show: RegionShow, skeleton: @Composable () -> Unit, content: @Composable () -> Unit) {
    val fade = rememberRegionAlpha(show)
    when (show) {
        RegionShow.SKELETON -> skeleton()
        RegionShow.CONTENT -> Box(
            modifier = Modifier.graphicsLayer {
                this.alpha = fade.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) { content() }
        RegionShow.GONE -> Unit
    }
}

/**
 * Line heights measured the way `BasicText` lays each line out, through one measurer and a memo, so
 * a skeleton never re-lays-out text per card. [sample] is the glyph the real line draws when that
 * is not a plain letter (a fallback font can be taller).
 */
internal class LineHeights(private val measurer: TextMeasurer, private val density: Density) {
    private val memo = HashMap<Pair<TextStyle, String>, Dp>()

    fun of(style: TextStyle, sample: String = "A"): Dp = memo.getOrPut(style to sample) {
        with(density) { measurer.measure(text = sample, style = style).size.height.toDp() }
    }
}

@Composable
internal fun rememberLineHeights(): LineHeights {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) { LineHeights(measurer, density) }
}

/** One text line's height for [style] (see [LineHeights]). Call once per composable, never in a loop. */
@Composable
internal fun rememberTextLineHeight(style: TextStyle, sample: String = "A"): Dp {
    val lines = rememberLineHeights()
    return remember(lines, style, sample) { lines.of(style, sample) }
}

/** A pulsing bar occupying [lineHeight] exactly (the bar itself is inset, the layout box is not). */
@Composable
internal fun SkeletonBar(pulse: State<Float>, width: Dp, lineHeight: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(width).height(lineHeight).padding(vertical = SKELETON_BAR_INSET)) {
        SkeletonBlock(pulse, SKELETON_BAR_RADIUS, Modifier.fillMaxSize())
    }
}

/** [SkeletonBar] for one line of text in [style]. */
@Composable
internal fun SkeletonLine(pulse: State<Float>, style: TextStyle, width: Dp, modifier: Modifier = Modifier) {
    SkeletonBar(pulse, width, rememberTextLineHeight(style), modifier)
}

private val EYEBROW_SKELETON_WIDTH = 150.dp
private val META_SKELETON_WIDTH = 240.dp
private val CREDITS_SKELETON_WIDTH = 280.dp
private val SPEC_SKELETON_WIDTH = 300.dp
private val PILL_RADIUS = 50.dp

@Composable
internal fun EyebrowSkeleton() {
    SkeletonLine(rememberSkeletonPulseAlpha(), EYEBROW_STYLE, EYEBROW_SKELETON_WIDTH)
}

/** The Series/Episode one-line meta row ([ItemBoundaryLine]). */
@Composable
internal fun MetaLineSkeleton() {
    SkeletonLine(rememberSkeletonPulseAlpha(), DETAIL_META_LINE_STYLE, META_SKELETON_WIDTH)
}

/** One credits line: as tall as the names text, the tallest cell of the real row. */
@Composable
internal fun CreditsSkeleton() {
    val lines = rememberLineHeights()
    val height = maxOf(
        lines.of(CREDITS_NAMES_STYLE),
        lines.of(CREDITS_LABEL_STYLE),
        lines.of(CREDITS_SEPARATOR_STYLE, BOX_SEPARATOR),
    )
    SkeletonBar(rememberSkeletonPulseAlpha(), CREDITS_SKELETON_WIDTH, height)
}

/** The outlined spec capsule: one line of 9sp values plus the capsule's own padding. */
@Composable
internal fun SpecStripSkeleton(modifier: Modifier = Modifier) {
    val lines = rememberLineHeights()
    val height = maxOf(lines.of(SPEC_TEXT_STYLE), lines.of(SPEC_TEXT_STYLE, BOX_SEPARATOR)) + SPEC_PILL_VPAD * 2
    SkeletonBlock(rememberSkeletonPulseAlpha(), PILL_RADIUS, modifier.size(SPEC_SKELETON_WIDTH, height))
}

/** A genre chip placeholder, [width] wide and a real chip tall. */
@Composable
internal fun GenreChipSkeleton(pulse: State<Float>, width: Dp, modifier: Modifier = Modifier) {
    val height = rememberTextLineHeight(GENRE_CHIP_STYLE) + GENRE_CHIP_VPAD * 2
    SkeletonBlock(pulse, PILL_RADIUS, modifier.size(width, height))
}

private val CAST_SKELETON_NAME_WIDTH = 56.dp
private val CAST_SKELETON_ROLE_WIDTH = 40.dp
private val CAST_SKELETON_HEADER_WIDTH = 40.dp
private const val CAST_SKELETON_MAX_CARDS = 12

/**
 * The Cast row at its final height: header (when [showHeader]), then round portraits with a name
 * bar and, when [showRole], a role bar, in the real card's 84dp columns. The circles exist only
 * while loading (docs/11 §Loading state); a loaded row never shows a portrait-less circle.
 */
@Composable
internal fun CastRowSkeleton(showHeader: Boolean, showRole: Boolean, contentWidth: Dp, modifier: Modifier = Modifier) {
    val pulse = rememberSkeletonPulseAlpha()
    val lines = rememberLineHeights()
    val headerHeight = lines.of(CAST_HEADER_STYLE)
    val nameHeight = lines.of(CAST_NAME_STYLE)
    val roleHeight = lines.of(CAST_ROLE_STYLE)
    val cards = (DetailFormatting.wholeCardCount(contentWidth.value, PAGE_MARGIN.value, CAST_CARD_WIDTH.value, CAST_ROW_GAP.value) + 1)
        .coerceAtMost(CAST_SKELETON_MAX_CARDS)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CAST_HEADER_GAP)) {
        if (showHeader) {
            SkeletonBar(pulse, CAST_SKELETON_HEADER_WIDTH, headerHeight, Modifier.padding(start = PAGE_MARGIN))
        }
        // Cards keep their fixed 84dp under the row's bounded width; the box clips the overflow.
        Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
            Row(
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(horizontal = PAGE_MARGIN),
                horizontalArrangement = Arrangement.spacedBy(CAST_ROW_GAP),
            ) {
                repeat(cards) {
                    Column(modifier = Modifier.width(CAST_CARD_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                        SkeletonBlock(pulse, CAST_AVATAR_SIZE / 2, Modifier.size(CAST_AVATAR_SIZE))
                        Spacer(modifier = Modifier.height(CAST_NAME_GAP))
                        SkeletonBar(pulse, CAST_SKELETON_NAME_WIDTH, nameHeight)
                        if (showRole) {
                            Spacer(modifier = Modifier.height(CAST_ROLE_GAP))
                            SkeletonBar(pulse, CAST_SKELETON_ROLE_WIDTH, roleHeight)
                        }
                    }
                }
            }
        }
    }
}

private val UP_NEXT_SKELETON_LABEL_WIDTH = 52.dp
private val UP_NEXT_SKELETON_META_WIDTH = 80.dp

/** The Up Next panel's frame, thumbnail and two-line title block, sized from the real panel's
 * constants.
 */
@Composable
internal fun UpNextSkeleton(modifier: Modifier = Modifier) {
    val pulse = rememberSkeletonPulseAlpha()
    Column(
        modifier = modifier
            .width(UP_NEXT_PANEL_WIDTH)
            .background(JellybeamTheme.Notte.copy(alpha = UP_NEXT_PANEL_FILL_ALPHA), UP_NEXT_PANEL_SHAPE)
            .border(1.dp, JellybeamTheme.Hairline, UP_NEXT_PANEL_SHAPE)
            .padding(UP_NEXT_PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(UP_NEXT_LABEL_GAP),
    ) {
        SkeletonLine(pulse, UP_NEXT_LABEL_STYLE, UP_NEXT_SKELETON_LABEL_WIDTH)
        Row(horizontalArrangement = Arrangement.spacedBy(UP_NEXT_THUMB_TITLE_GAP)) {
            SkeletonBlock(pulse, UP_NEXT_THUMB_RADIUS, Modifier.size(UP_NEXT_THUMB_WIDTH, UP_NEXT_THUMB_HEIGHT))
            Column(
                modifier = Modifier.width(UP_NEXT_PANEL_WIDTH - UP_NEXT_PANEL_PADDING * 2 - UP_NEXT_THUMB_WIDTH - UP_NEXT_THUMB_TITLE_GAP),
                verticalArrangement = Arrangement.spacedBy(UP_NEXT_TITLE_META_GAP),
            ) {
                SkeletonBlock(pulse, SKELETON_BAR_RADIUS, Modifier.fillMaxWidth().height(UP_NEXT_TITLE_HEIGHT))
                SkeletonLine(pulse, UP_NEXT_META_STYLE, UP_NEXT_SKELETON_META_WIDTH)
            }
        }
    }
}

private val SEASON_SKELETON_LABEL_WIDTH = 56.dp
private const val SEASON_SKELETON_CHIPS = 4

/** The season label + chip row, one chip tall, at the same page margin as the real row. */
@Composable
internal fun SeasonChipsSkeleton(modifier: Modifier = Modifier) {
    val pulse = rememberSkeletonPulseAlpha()
    val chipHeight = rememberTextLineHeight(SEASON_CHIP_STYLE) + SEASON_CHIP_VPAD * 2
    Row(
        modifier = modifier.padding(horizontal = PAGE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(SEASON_ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonLine(pulse, CAST_HEADER_STYLE, SEASON_SKELETON_LABEL_WIDTH)
        Row(horizontalArrangement = Arrangement.spacedBy(SEASON_CHIP_GAP)) {
            repeat(SEASON_SKELETON_CHIPS) {
                SkeletonBlock(pulse, PILL_RADIUS, Modifier.size(SEASON_CHIP_MIN_WIDTH, chipHeight))
            }
        }
    }
}

/** The episode shelf's loading row: four cards matching [EpisodeGridCard]'s footprint. */
@Composable
internal fun EpisodeShelfSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = PAGE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(EPISODE_SHELF_GAP),
    ) {
        repeat(4) { EpisodeGridSkeletonCard() }
    }
}
