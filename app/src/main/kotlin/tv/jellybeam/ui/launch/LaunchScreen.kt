package tv.jellybeam.ui.launch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.AccentScheme
import tv.jellybeam.AccentSchemes
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.LocalAccent
import tv.jellybeam.R
import tv.jellybeam.i18n.UiStrings
import tv.jellybeam.i18n.rememberUiStrings
import tv.jellybeam.ui.theme.JellybeamWordmark

/**
 * The pre-Home launch screen (docs/brand.md §6.3): mascot, rayed wordmark, and a status row
 * naming [host] (verbatim, as saved) or reporting [unreachable]. Plain composable with no effect of
 * its own, so it never delays first paint (docs/10-perf-logging.md).
 */
@Composable
fun LaunchScreen(host: String?, unreachable: Boolean, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalAccent provides launchAccent()) {
        LaunchContent(host, unreachable, modifier)
    }
}

@Composable
private fun LaunchContent(host: String?, unreachable: Boolean, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.jb_mascot_fast),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(280.dp),
        )
        Spacer(Modifier.height(22.dp))
        JellybeamWordmark(size = 66.sp, rays = true)
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(LocalAccent.current.accentInk, CircleShape))
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = launchStatusText(rememberUiStrings(), host, unreachable),
                style = TextStyle(
                    fontFamily = JellybeamTheme.MartianMono,
                    color = JellybeamTheme.Grigio,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}

/** §6.3 status text: `CONNECTING · HOST`, or `SERVER UNREACHABLE · HOST` once [unreachable]; the
 * bare word with no host when [host] isn't known yet (no saved server, or not resolved yet). */
fun launchStatusText(strings: UiStrings, host: String?, unreachable: Boolean): String {
    val prefix = strings.get(if (unreachable) R.string.launch_server_unreachable else R.string.launch_connecting)
    return if (host != null) "$prefix · $host" else prefix
}

/** §6.3: the launch screen is the brand mark, so it is always the default flavour, whatever accent is picked. */
fun launchAccent(): AccentScheme = AccentSchemes.first()

/** §6.3: the longest the launch screen holds for the Home prefetch; a slower first load shows Home's skeleton. */
const val LAUNCH_HOLD_MAX_MS = 1_000L

/** §6.3: hold only on a cold start landing on Home itself, so external launches and a startup library never wait. */
fun holdLaunchForHome(coldStart: Boolean, externalLaunch: Boolean, startupLibrary: Boolean): Boolean =
    coldStart && !externalLaunch && !startupLibrary
