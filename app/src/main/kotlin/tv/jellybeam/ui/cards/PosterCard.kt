package tv.jellybeam.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import uniffi.jellybeam_core.Card
import uniffi.jellybeam_core.ImageKind

/**
 * docs/07 §1: fixed 2:3 poster cell width for Latest shelves. 108dp puts ~7.6 posters
 * across a 1080p TV's 896dp shelf at the app-wide 12dp cell gap (`CELL_GAP`) -- 7 full
 * cards plus a bleeding 8th.
 */
val POSTER_CELL_WIDTH = 108.dp

/** One "Latest in {library}" poster tile: 2:3 art, a fixed 2-line title block, badges and a
 * watch-progress bar per docs/07 §1-2.
 */
@Composable
fun PosterCard(
    card: Card,
    isFocused: Boolean,
    imageUrl: (itemId: String, kind: ImageKind, tag: String) -> String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = POSTER_CELL_WIDTH,
) {
    val height = width * 1.5f
    // Computed inline, not remembered: a refreshed Card with the same id must recompute
    // rather than show the first composition's stale value (docs/07-home-browse-behavior.md §1).
    val artSource = CardFormatting.posterArtSource(card)
    val progress = CardFormatting.watchProgress(card)
    val indicator = CardFormatting.watchIndicator(card, progress)

    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArtBox(width = width, height = height, isFocused = isFocused) {
            CardArtImage(
                source = artSource,
                imageUrl = imageUrl,
                itemName = card.name,
                contentAlpha = if (card.isVirtual) 0.4f else 1f,
                blurhash = card.blurhash,
                modifier = Modifier.fillMaxSize(),
            )
            progress?.let { WatchProgressBar(it) }
            WatchBadge(indicator)
        }
        PosterTitleBlock(width = width, title = card.name, year = card.productionYear, isFocused = isFocused)
    }
}

/**
 * docs/07 §0.5: fixed 2-line title (33dp reserved) plus 1-line year meta (16dp reserved),
 * reserved regardless of content so a row's cells stay level. Cross-screen rule, so
 * [PosterCard] (shared with Library/Search) follows it too.
 */
@Composable
private fun PosterTitleBlock(width: Dp, title: String, year: Int?, isFocused: Boolean) {
    val titleColor = if (isFocused) JellybeamTheme.Panna else JellybeamTheme.Panna2
    val metaColor = if (isFocused) JellybeamTheme.Panna2 else JellybeamTheme.Grigio

    Column(modifier = Modifier.width(width)) {
        CardTitleText(title = title, color = titleColor)
        BasicText(
            text = year?.toString().orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(16.dp),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                color = metaColor,
                fontSize = 11.sp,
            ),
        )
    }
}
