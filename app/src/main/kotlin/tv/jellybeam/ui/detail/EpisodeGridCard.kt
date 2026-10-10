package tv.jellybeam.ui.detail

import tv.jellybeam.i18n.rememberUiStrings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.ui.cards.WIDE_ART_ASPECT
import tv.jellybeam.ui.cards.ArtBox
import tv.jellybeam.ui.cards.CardArtImage
import tv.jellybeam.ui.cards.CardFormatting
import tv.jellybeam.ui.cards.CardTitleText
import tv.jellybeam.ui.cards.WatchIndicator
import tv.jellybeam.ui.cards.WatchProgressBar
import tv.jellybeam.ui.cards.rememberSkeletonPulseAlpha
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind

/** docs/07 §3 item 7: Series season shelf episode card, 172x97dp 16:9 art. */
val EPISODE_CARD_WIDTH = 172.dp

/** §3 item 7's watched-tick badge: 19dp circle, `rgba(11,9,8,0.85)` fill, pistachio glyph. */
private val WATCHED_TICK_SIZE = 19.dp
private val WATCHED_TICK_FILL = JellybeamTheme.Notte.copy(alpha = 0.85f)

/** §3 item 7's meta line ("23m · Sep 24, 2007"): one reserved 11sp line below the two-line
 * [CardTitleText] title.
 */
private val EPISODE_META_ZONE_HEIGHT = 16.dp

/**
 * One episode tile in the Series season shelf (§3 item 7): 16:9 art
 * ([CardFormatting.railArtSource]), a bottom progress bar when resumable, a top-right watched
 * tick when played, [CardTitleText]'s fixed two-line title, and a reserved runtime+air-date line,
 * omitted when the server has none. Focus here is ring+scale+brightness only, owned by [ArtBox].
 */
@Composable
fun EpisodeGridCard(
    card: Card,
    isFocused: Boolean,
    imageUrl: (itemId: String, kind: ImageKind, tag: String) -> String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = EPISODE_CARD_WIDTH,
) {
    val height = width * 9f / 16f
    val strings = rememberUiStrings()
    // Computed inline, not remembered: a refreshed Card with the same id must recompute
    // rather than show the first composition's stale value (docs/07-home-browse-behavior.md §1).
    val artSource = CardFormatting.railArtSource(card)
    val title = CardFormatting.episodeTitle(strings, card.name, card.indexNumber)
    val metaLine = if (card.isVirtual) {
        CardFormatting.virtualStatusLabel(strings, card.premiereDate)
    } else {
        DetailFormatting.runtimeAndDateLine(strings, card.runtimeTicks, card.premiereDate).orEmpty()
    }
    val progress = CardFormatting.watchProgress(card)
    val indicator = CardFormatting.watchIndicator(card, progress)

    Column(
        // tier-2 media-key direct play: Enter/click here opens the episode's Detail page, never
        // plays it.
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtBox(width = width, height = height, isFocused = isFocused) {
            CardArtImage(
                source = artSource,
                imageUrl = imageUrl,
                itemName = card.name,
                contentAlpha = if (card.isVirtual) 0.4f else 1f,
                blurhash = card.blurhash,
                modifier = Modifier.fillMaxSize(),
                aspect = WIDE_ART_ASPECT,
            )
            // Mutually exclusive: [CardFormatting.watchIndicator] returns WatchedCheck only when
            // [progress] is null.
            progress?.let { WatchProgressBar(it) }
            if (indicator == WatchIndicator.WatchedCheck) EpisodeWatchedTick()
        }
        Column(modifier = Modifier.width(width)) {
            CardTitleText(title = title, color = JellybeamTheme.Panna)
            BasicText(
                text = metaLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(EPISODE_META_ZONE_HEIGHT),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 11.sp),
            )
        }
    }
}

/** Watched tick, top-right of the art. */
@Composable
private fun BoxScope.EpisodeWatchedTick() {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(WATCHED_TICK_SIZE)
            .background(WATCHED_TICK_FILL, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "✓",
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.Bold,
                color = JellybeamTheme.Pistacchio,
                fontSize = 11.sp,
            ),
        )
    }
}

/** Loading stand-in for [EpisodeGridCard]: matches its art box + two-zone text footprint exactly,
 * so swapping in real cells doesn't change the shelf's height. Not focusable/clickable.
 */
@Composable
fun EpisodeGridSkeletonCard(modifier: Modifier = Modifier, width: Dp = EPISODE_CARD_WIDTH) {
    val height = width * 9f / 16f
    val pulseAlpha = rememberSkeletonPulseAlpha()

    Column(
        modifier = modifier.graphicsLayer { alpha = pulseAlpha.value },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width, height)
                .clip(RoundedCornerShape(2.dp))
                .background(JellybeamTheme.SurfaceRaised),
        )
        Column(modifier = Modifier.width(width)) {
            CardTitleText(title = "", color = JellybeamTheme.Panna)
            Box(modifier = Modifier.height(EPISODE_META_ZONE_HEIGHT))
        }
    }
}
