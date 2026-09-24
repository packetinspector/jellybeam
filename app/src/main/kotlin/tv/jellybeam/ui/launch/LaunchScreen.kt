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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.theme.JellybeamWordmark

/**
 * The pre-Home launch screen (docs/brand.md §6.3): mascot, rayed wordmark, and a status row
 * naming [host] (verbatim, as saved) or reporting [unreachable]. Plain composable with no effect of
 * its own, so it never delays first paint (docs/10-perf-logging.md).
 */
@Composable
fun LaunchScreen(host: String?, unreachable: Boolean, modifier: Modifier = Modifier) {
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
            Box(Modifier.size(6.dp).background(JellybeamTheme.Pistacchio, CircleShape))
            Spacer(Modifier.width(6.dp))
            BasicText(
                text = launchStatusText(host, unreachable),
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
fun launchStatusText(host: String?, unreachable: Boolean): String {
    val prefix = if (unreachable) "SERVER UNREACHABLE" else "CONNECTING"
    return if (host != null) "$prefix · $host" else prefix
}
