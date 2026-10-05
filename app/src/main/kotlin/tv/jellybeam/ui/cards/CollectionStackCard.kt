package tv.jellybeam.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.ui.detail.CollectionFormatting
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind

/** Diagonal offset of each fanned poster, as a fraction of the cell width. */
private const val STACK_STEP_FRACTION = 0.07f

/** Black wash over the first/second poster behind the front one. */
private val STACK_BACK_DIM = floatArrayOf(0.35f, 0.55f)

/**
 * docs/07 §Collection card: a library grid cell for a BoxSet -- the front poster with up to two
 * more fanned up-right behind it, all inside the plain [PosterCard] cell box so grid math and
 * focus are untouched; the focus ring/scale belong to the front poster only. [members] is the
 * collection's members, loaded lazily (`null` = not loaded yet, never blocks the cell).
 */
@Composable
fun CollectionStackCard(
    card: Card,
    members: List<Card>?,
    isFocused: Boolean,
    imageUrl: (itemId: String, kind: ImageKind, tag: String) -> String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = POSTER_CELL_WIDTH,
) {
    val height = width * 1.5f
    val step = width * STACK_STEP_FRACTION
    val frontWidth = width - step * (CollectionFormatting.STACK_DEPTH - 1)
    val frontHeight = frontWidth * 1.5f
    val frontTop = height - frontHeight
    val layers = CollectionFormatting.stackLayers(card, members.orEmpty())
    val caption = CollectionFormatting.stackCaption(card, members)
    val indicator = CollectionFormatting.stackIndicator(members)

    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(width, height)) {
            // Farthest first so each nearer poster paints over it.
            for (depth in layers.lastIndex downTo 1) {
                val layer = layers[depth]
                val source = CardFormatting.posterArtSource(layer)
                Box(
                    modifier = Modifier
                        .offset(x = step * depth, y = frontTop - step * depth)
                        .size(frontWidth, frontHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(JellybeamTheme.SurfaceRaised),
                ) {
                    if (source != ArtSource.None) {
                        CardArtImage(
                            source = source,
                            imageUrl = imageUrl,
                            itemName = layer.name,
                            contentAlpha = 1f,
                            blurhash = layer.blurhash,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = STACK_BACK_DIM[depth - 1])))
                }
            }
            ArtBox(width = frontWidth, height = frontHeight, isFocused = isFocused, modifier = Modifier.offset(y = frontTop)) {
                val front = layers.firstOrNull()
                val source = front?.let(CardFormatting::posterArtSource) ?: ArtSource.None
                // A still-loading preview leaves the flat tile; the name placeholder is only for a
                // collection known to have nothing to show.
                if (front != null || members != null) {
                    CardArtImage(
                        source = source,
                        imageUrl = imageUrl,
                        itemName = card.name,
                        contentAlpha = 1f,
                        blurhash = front?.blurhash,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                WatchBadge(indicator)
            }
        }
        PosterTitleBlock(width = width, title = card.name, meta = caption, isFocused = isFocused, monoMeta = true)
    }
}
