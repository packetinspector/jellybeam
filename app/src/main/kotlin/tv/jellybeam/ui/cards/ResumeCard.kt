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
 * A Continue Watching / Next Up tile. Deviates from docs/07 §1's mixed-aspect single row:
 * every card is uniform 16:9 at [rowHeight], using [CardFormatting.resumeArtSource]'s
 * backdrop chain rather than a movie's 2:3 poster. Per §7, the remaining-time/virtual-status
 * pill lives on the art ([TimingPill]); below it sits a fixed 2-line title plus one
 * series/season line. No `rowDim` param (§0.4): the ring alone carries focus.
 */
@Composable
fun ResumeCard(
    card: Card,
    isFocused: Boolean,
    rowHeight: Dp,
    imageUrl: (itemId: String, kind: ImageKind, tag: String) -> String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEpisode = card.itemType == "Episode"
    val width = rowHeight * (16f / 9f)
    // Computed inline, not remembered: a refreshed Card with the same id must recompute
    // rather than show the first composition's stale value (docs/07-home-browse-behavior.md §1).
    val artSource = CardFormatting.resumeArtSource(card)
    val progress = CardFormatting.watchProgress(card)
    val indicator = CardFormatting.watchIndicator(card, progress)
    val timingLabel = CardFormatting.resumeTimingLabel(card)
    val seriesSeasonLine = CardFormatting.resumeSeriesSeasonLine(card)
    val title = if (isEpisode) CardFormatting.episodeTitle(card.name, card.indexNumber) else card.name

    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArtBox(width = width, height = rowHeight, isFocused = isFocused) {
            CardArtImage(
                source = artSource,
                imageUrl = imageUrl,
                itemName = card.name,
                contentAlpha = if (card.isVirtual) 0.4f else 1f,
                blurhash = card.blurhash,
                modifier = Modifier.fillMaxSize(),
            )
            timingLabel?.let { TimingPill(it) }
            progress?.let { WatchProgressBar(it) }
            WatchBadge(indicator)
        }
        ResumeTitleBlock(width = width, title = title, seriesSeasonLine = seriesSeasonLine, isFocused = isFocused)
    }
}

/** docs/07 §7/§0.5: fixed 2-line title then one series/season line, both heights reserved so a
 * Movie card (no series/season line) still matches an Episode card's height.
 */
@Composable
private fun ResumeTitleBlock(width: Dp, title: String, seriesSeasonLine: String?, isFocused: Boolean) {
    val titleColor = if (isFocused) JellybeamTheme.Panna else JellybeamTheme.Panna2
    val metaColor = if (isFocused) JellybeamTheme.Panna2 else JellybeamTheme.Grigio

    Column(modifier = Modifier.width(width)) {
        CardTitleText(title = title, color = titleColor)
        BasicText(
            text = seriesSeasonLine.orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(16.dp),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = metaColor, fontSize = 11.5.sp),
        )
    }
}
