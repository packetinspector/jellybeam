package tv.jellybeam.ui.discover

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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import tv.jellybeam.AppForeground
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.ui.cards.ArtBox
import tv.jellybeam.ui.cards.CardTitleText
import tv.jellybeam.ui.cards.IMAGE_LOAD_MAX_RETRIES
import tv.jellybeam.ui.cards.PlaceholderTile
import tv.jellybeam.ui.cards.POSTER_CELL_WIDTH
import tv.jellybeam.ui.cards.imageRetryDelayMs
import uniffi.jellybeam_core.SeerrAvailability

/**
 * A Seerr title's poster tile -- same [ArtBox]/[CardTitleText] geometry/focus recipe as
 * [tv.jellybeam.ui.cards.PosterCard] (docs/07 §1), not that composable directly: it's hard-coupled to
 * a
 * Jellyfin [uniffi.jellybeam_core.Card], whereas a Seerr card's art is already a fully-built URL
 * (docs/14 "Image URLs"). Corner badge per [availabilityBadge].
 */
@Composable
fun SeerrPosterCard(
    posterUrl: String?,
    title: String,
    year: Int?,
    availability: SeerrAvailability,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = POSTER_CELL_WIDTH,
) {
    val height = width * 1.5f
    val badge = remember(availability) { availabilityBadge(availability) }

    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArtBox(width = width, height = height, isFocused = isFocused) {
            SeerrPosterArt(posterUrl = posterUrl, title = title, modifier = Modifier.fillMaxSize())
            SeerrAvailabilityBadgeOverlay(badge)
        }
        SeerrPosterTitleBlock(width = width, title = title, year = year, isFocused = isFocused)
    }
}

/**
 * [posterUrl] is already the fully-built image URL (docs/14 "Image URLs"), straight to Coil; `null`
 * falls back to [PlaceholderTile]. Same bounded-retry recipe as [tv.jellybeam.ui.cards.CardArtImage]:
 * backoff
 * via [imageRetryDelayMs]/[IMAGE_LOAD_MAX_RETRIES], plus an immediate retry on
 * [AppForeground.resumeCount] changing.
 */
@Composable
private fun SeerrPosterArt(posterUrl: String?, title: String, modifier: Modifier = Modifier) {
    if (posterUrl == null) {
        PlaceholderTile(name = title, modifier = modifier)
        return
    }
    var painterState by remember(posterUrl) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    var retryAttempt by remember(posterUrl) { mutableIntStateOf(0) }
    var retryToken by remember(posterUrl) { mutableIntStateOf(0) }
    LaunchedEffect(posterUrl, painterState) {
        if (painterState is AsyncImagePainter.State.Error && retryAttempt < IMAGE_LOAD_MAX_RETRIES) {
            delay(imageRetryDelayMs(retryAttempt))
            retryAttempt++
            retryToken++
        }
    }

    val resumeCount = AppForeground.resumeCount.intValue
    val resumeCountAtEntry = remember(posterUrl) { resumeCount }
    LaunchedEffect(resumeCount) {
        if (resumeCount != resumeCountAtEntry && painterState is AsyncImagePainter.State.Error) {
            retryAttempt = 0
            retryToken++
        }
    }

    val context = LocalContext.current
    val model = remember(posterUrl, retryToken) {
        if (retryToken == 0) {
            posterUrl
        } else {
            ImageRequest.Builder(context)
                .data(posterUrl)
                .setParameter("retry", retryToken, memoryCacheKey = null)
                .build()
        }
    }

    Box(modifier = modifier) {
        if (painterState !is AsyncImagePainter.State.Success) {
            PlaceholderTile(name = title, modifier = Modifier.fillMaxSize())
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { painterState = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** [tv.jellybeam.ui.cards.WatchBadge]'s visual recipe re-skinned per [SeerrAvailabilityBadge]'s three
 * non-empty cases.
 */
@Composable
private fun BoxScope.SeerrAvailabilityBadgeOverlay(badge: SeerrAvailabilityBadge) {
    when (badge) {
        SeerrAvailabilityBadge.None -> Unit

        SeerrAvailabilityBadge.Check -> Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(JellybeamTheme.Pistacchio, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "✓",
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Notte, fontSize = 12.sp),
            )
        }

        SeerrAvailabilityBadge.Dot -> Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(10.dp)
                .background(JellybeamTheme.Panna2, CircleShape),
        )

        SeerrAvailabilityBadge.Half -> Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                // Same alpha as WatchBadge's dark chip background (tv.jellybeam.ui.cards.CardArt.kt).
                .background(JellybeamTheme.Notte.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "½",
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna2, fontSize = 10.sp),
            )
        }
    }
}

/** [tv.jellybeam.ui.cards.PosterCard]'s `PosterTitleBlock`, re-created since that one is `private` to
 * its file.
 */
@Composable
private fun SeerrPosterTitleBlock(width: Dp, title: String, year: Int?, isFocused: Boolean) {
    val titleColor = if (isFocused) JellybeamTheme.Panna else JellybeamTheme.Panna2
    val metaColor = if (isFocused) JellybeamTheme.Panna2 else JellybeamTheme.Grigio

    Column(modifier = Modifier.width(width)) {
        CardTitleText(title = title, color = titleColor)
        BasicText(
            text = year?.toString().orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(16.dp),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = metaColor, fontSize = 11.sp),
        )
    }
}
