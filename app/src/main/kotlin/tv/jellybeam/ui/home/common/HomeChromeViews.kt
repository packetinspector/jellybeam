package tv.jellybeam.ui.home.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.ui.common.emptyLibrarySpecLine

/**
 * The top-right clock, gated on `Settings.showClock` (default on). Format is the device's
 * locale-aware short time pattern via [android.text.format.DateFormat.getTimeFormat], never a
 * hand-rolled `SimpleDateFormat`. Ticks once a minute, aligned to the wall-clock minute boundary
 * so it never accumulates drift.
 */
@Composable
internal fun HomeClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            val millisToNextMinute = 60_000L - (nowMillis % 60_000L)
            delay(millisToNextMinute)
        }
    }
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val text = remember(nowMillis, timeFormat) { timeFormat.format(java.util.Date(nowMillis)) }
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = JellybeamTheme.MartianMono,
            color = JellybeamTheme.Panna2,
            fontSize = 11.sp,
            letterSpacing = (-0.02).em,
        ),
    )
}

/**
 * Home with a reachable server and no items anywhere (docs/brand.md §6.2): the curious
 * mascot on Notte, one factual title and body, and the spec strip; no button, since there is
 * nothing on the TV side to act on. The only §5 tier-2 surface on Home: it never renders once a
 * shelf exists.
 */
@Composable
internal fun EmptyLibraryState(host: String?, libraries: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = EMPTY_STATE_TOP_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.jb_mascot_curious),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(EMPTY_STATE_MASCOT_WIDTH),
        )
        BasicText(
            text = stringResource(R.string.home_empty_title),
            modifier = Modifier.padding(top = 15.dp),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em,
                color = JellybeamTheme.Panna,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = stringResource(R.string.home_empty_body),
            modifier = Modifier.padding(top = 8.dp).widthIn(max = EMPTY_STATE_BODY_MAX_WIDTH),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                color = JellybeamTheme.Panna2,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = emptyLibrarySpecLine(rememberUiStrings(), host, libraries, items = 0),
            modifier = Modifier.padding(top = 18.dp),
            style = TextStyle(
                fontFamily = JellybeamTheme.MartianMono,
                color = JellybeamTheme.Grigio,
                fontSize = 11.sp,
            ),
        )
    }
}

private val EMPTY_STATE_TOP_PADDING = 48.dp
private val EMPTY_STATE_MASCOT_WIDTH = 180.dp
private val EMPTY_STATE_BODY_MAX_WIDTH = 450.dp

/**
 * Cold-start / background-sync status pill, positioned by the layout.
 * Same visual recipe as the player's `BufferingPill` (`PlaybackScreen.kt`): a rounded
 * [JellybeamTheme.SurfaceRaised] pill around small [JellybeamTheme.Grigio] text.
 */
@Composable
internal fun HomeSyncStatusPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(JellybeamTheme.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                color = JellybeamTheme.Grigio,
                fontSize = 13.sp,
            ),
        )
    }
}
