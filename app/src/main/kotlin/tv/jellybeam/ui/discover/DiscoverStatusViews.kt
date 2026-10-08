package tv.jellybeam.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.cards.POSTER_CELL_WIDTH
import tv.jellybeam.ui.cards.rememberSkeletonPulseAlpha
import tv.jellybeam.ui.settings.SettingsChip

/**
 * Shared inline-message/skeleton composables for every Discover screen's
 * isLoading/notConfigured/error/
 * empty branch (docs/14-seerr-discover.md), pulled into one file rather than duplicated per screen.
 */
internal val DISCOVER_PAGE_MARGIN = 32.dp

@Composable
internal fun DiscoverMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(horizontal = DISCOVER_PAGE_MARGIN)) {
        BasicText(text = text, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 16.sp))
    }
}

@Composable
internal fun DiscoverErrorMessage(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = DISCOVER_PAGE_MARGIN, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(text = text, style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 16.sp))
        SettingsChip(
            label = stringResource(R.string.discover_error_retry),
            selected = false,
            onSelect = onRetry,
            focusRequester = retryFocusRequester,
        )
    }
}

/** A row of pulsing poster-shaped blocks -- [tv.jellybeam.ui.home.HomeScreen]'s loading-skeleton
 * philosophy, reduced to a Discover grid/shelf's simplest useful shape.
 */
@Composable
internal fun DiscoverPosterSkeletonRow(count: Int, modifier: Modifier = Modifier) {
    val pulseAlpha = rememberSkeletonPulseAlpha()
    androidx.compose.foundation.layout.Row(
        modifier = modifier.padding(horizontal = DISCOVER_PAGE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(count) {
            Box(
                modifier = Modifier
                    .size(POSTER_CELL_WIDTH, POSTER_CELL_WIDTH * 1.5f)
                    .graphicsLayer { alpha = pulseAlpha.value }
                    .background(JellybeamTheme.SurfaceRaised),
            )
        }
    }
}
